package com.starlane.core.engine

import com.starlane.core.crypto.CipherSuite
import com.starlane.core.crypto.Ecdh
import com.starlane.core.crypto.FrameCipher
import com.starlane.core.crypto.Hkdf
import com.starlane.core.crypto.Identity
import com.starlane.core.crypto.randomBytes
import com.starlane.core.crypto.sha256
import com.starlane.core.link.Link
import com.starlane.core.proto.Capabilities
import com.starlane.core.proto.PROTOCOL_VERSION
import com.starlane.core.proto.PeerInfo
import com.starlane.core.proto.Profile
import com.starlane.core.proto.T
import com.starlane.core.util.ProtocolException
import com.starlane.core.util.WireReader
import com.starlane.core.util.WireWriter
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream

/**
 * Authenticated, encrypted record channel over any [Link].
 *
 * Handshake (both sides send simultaneously, so it costs a single round trip):
 *  1. HELLO (cleartext): magic, version, ephemeral ECDH P-256 key, long-term identity key,
 *     random nonce, public profile and capabilities.
 *  2. Both derive keys with HKDF-SHA256 over the ECDH secret, salted with the hash of both
 *     HELLOs (the transcript), giving forward secrecy and binding everything that was said.
 *  3. AUTH (encrypted): ECDSA signature of the transcript with the identity key. This proves
 *     possession of the private key behind the announced device id, so peers cannot be
 *     impersonated and a man in the middle cannot complete the handshake.
 *
 * Records: `u32 length || AEAD(type || body)`. AES-128-GCM or ChaCha20-Poly1305 is picked by
 * benchmarking both devices. Nonces are counters, so replayed, dropped or reordered records fail
 * authentication.
 */
class Channel private constructor(
    val link: Link,
    val peer: PeerInfo,
    val suite: CipherSuite,
    val initiator: Boolean,
    private val tx: FrameCipher,
    private val rx: FrameCipher,
    private val inp: DataInputStream,
    private val out: OutputStream,
    val maxPayload: Int,
) {
    private val writeLock = java.util.concurrent.locks.ReentrantLock()
    private var outBuf = ByteArray(4 + 1 + 256 + CipherSuite.TAG)
    private var inBuf = ByteArray(1024)
    private var plainBuf = ByteArray(1024)
    private val typeByte = ByteArray(1)

    @Volatile var closed = false
        private set

    @Volatile var lastReceiveAt = System.currentTimeMillis()
        private set

    @Volatile var lastSendAt = System.currentTimeMillis()
        private set

    private val maxRecord = maxOf(maxPayload + 8192, MAX_CONTROL)

    /** Sends a record whose body is `src[off, off+len)`. Thread-safe. */
    fun send(type: Int, src: ByteArray, off: Int = 0, len: Int = src.size) {
        if (len > maxRecord) throw IllegalArgumentException("record too large")
        writeLock.lock()
        try {
            if (closed) throw IOException("channel closed")
            val ctLen = 1 + len + CipherSuite.TAG
            // Oversized control records use a temporary buffer so we don't retain big arrays.
            val big = 4 + ctLen > maxPayload + 8192 + 64
            val ob = if (big) ByteArray(4 + ctLen) else {
                if (outBuf.size < 4 + ctLen) outBuf = ByteArray(4 + ctLen)
                outBuf
            }
            typeByte[0] = type.toByte()
            val n = tx.encrypt(typeByte, 1, src, off, len, ob, 4)
            ob[0] = (n ushr 24).toByte(); ob[1] = (n ushr 16).toByte()
            ob[2] = (n ushr 8).toByte(); ob[3] = n.toByte()
            out.write(ob, 0, 4 + n)
            out.flush()
            lastSendAt = System.currentTimeMillis()
        } finally {
            writeLock.unlock()
        }
    }

    /** Sends a small control record only if no other writer is busy (never blocks the caller). */
    fun trySend(type: Int, w: WireWriter? = null): Boolean {
        if (closed || !writeLock.tryLock()) return false
        return try {
            if (w == null) send(type) else send(type, w)
            true
        } catch (e: Exception) {
            false
        } finally {
            writeLock.unlock()
        }
    }

    fun send(type: Int, w: WireWriter) = send(type, w.buf, 0, w.size)
    fun send(type: Int) = send(type, EMPTY, 0, 0)

    /**
     * Blocks until the next record arrives and hands it to [handler]. The body buffer is reused,
     * so the handler must consume it before returning (this gives natural backpressure: we never
     * read faster than the disk accepts data).
     */
    fun receive(handler: (type: Int, body: ByteArray, off: Int, len: Int) -> Unit) {
        val n = inp.readInt()
        if (n < 1 + CipherSuite.TAG || n > maxRecord + 1 + CipherSuite.TAG) throw ProtocolException("bad record length $n")
        val big = n > maxPayload + 8192 + 64
        val ib = if (big) ByteArray(n) else { if (inBuf.size < n) inBuf = ByteArray(n); inBuf }
        inp.readFully(ib, 0, n)
        val pb = if (big) ByteArray(n) else { if (plainBuf.size < n) plainBuf = ByteArray(n); plainBuf }
        val pl = try {
            rx.decrypt(ib, 0, n, pb, 0)
        } catch (e: Exception) {
            throw ProtocolException("record authentication failed")
        }
        lastReceiveAt = System.currentTimeMillis()
        handler(pb[0].toInt() and 0xFF, pb, 1, pl - 1)
    }

    fun close() {
        closed = true
        link.close()
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), 'N'.code.toByte())
        private const val MAX_HELLO = 32 * 1024
        /** Upper bound for control records such as large manifests. */
        const val MAX_CONTROL = 16 * 1024 * 1024

        /**
         * Runs the handshake. Throws on any failure, including when [expectedDeviceId] is given
         * and the authenticated peer has a different id (protects against spoofed discovery).
         */
        fun handshake(
            link: Link,
            identity: Identity,
            profile: Profile,
            caps: Capabilities,
            initiator: Boolean,
            expectedDeviceId: String? = null,
            timeoutMs: Int = 15_000,
        ): Channel {
            link.setReadTimeout(timeoutMs)
            val inp = DataInputStream(BufferedInputStream(link.input, 64 * 1024))
            val out = link.output
            val eph = Ecdh.generate()
            val hello = WireWriter(1024)
                .raw(MAGIC).u8(PROTOCOL_VERSION).u8(if (initiator) 0 else 1)
                .bytes(eph.public.encoded).bytes(identity.publicEncoded).raw(randomBytes(32))
                .strMap(profile.toMap()).strMap(caps.toMap())
                .toByteArray()
            writeRaw(out, hello)
            val peerHello = readRaw(inp, MAX_HELLO)

            val r = WireReader(peerHello)
            if (!r.raw(4).contentEquals(MAGIC)) throw ProtocolException("not a Starlane peer")
            val ver = r.u8()
            if (ver != PROTOCOL_VERSION) throw ProtocolException("unsupported protocol version $ver")
            val peerRole = r.u8()
            if (peerRole == (if (initiator) 0 else 1)) throw ProtocolException("role conflict")
            val peerEph = r.bytes(512)
            val peerId = r.bytes(512)
            r.raw(32)
            val peerProfile = Profile.fromMap(r.strMap())
            val peerCaps = Capabilities.fromMap(r.strMap())

            val peerDeviceId = Identity.deviceIdOf(peerId)
            if (peerId.contentEquals(identity.publicEncoded)) throw ProtocolException("connected to self")
            if (expectedDeviceId != null && expectedDeviceId != peerDeviceId) {
                throw ProtocolException("peer identity does not match the selected device")
            }

            val transcript = if (initiator) sha256(hello, peerHello) else sha256(peerHello, hello)
            val shared = Ecdh.agree(eph, peerEph)
            val prk = Hkdf.extract(transcript, shared)
            val okm = Hkdf.expand(prk, "starlane v1 record keys", 72)
            val suite = chooseSuite(caps, peerCaps)
            val kI = okm.copyOfRange(0, suite.keyLen)
            val kR = okm.copyOfRange(32, 32 + suite.keyLen)
            val sI = okm.copyOfRange(64, 68)
            val sR = okm.copyOfRange(68, 72)
            val tx = if (initiator) FrameCipher(suite, kI, sI) else FrameCipher(suite, kR, sR)
            val rx = if (initiator) FrameCipher(suite, kR, sR) else FrameCipher(suite, kI, sI)

            val peerInfo = PeerInfo(peerDeviceId, Identity.fingerprintOf(peerId), peerProfile, peerCaps)
            val maxPayload = maxOf(caps.maxChunk, peerCaps.maxChunk)
            val ch = Channel(link, peerInfo, suite, initiator, tx, rx, inp, out, maxPayload)

            val ownRole = if (initiator) "I" else "R"
            val sig = identity.sign(authData(ownRole, transcript))
            ch.send(T.AUTH, WireWriter(128).bytes(sig))
            var ok = false
            ch.receive { type, body, off, len ->
                if (type != T.AUTH) throw ProtocolException("expected AUTH")
                val peerSig = WireReader(body, off, off + len).bytes(256)
                ok = Identity.verify(peerId, authData(if (initiator) "R" else "I", transcript), peerSig)
            }
            if (!ok) {
                link.close()
                throw ProtocolException("peer authentication failed")
            }
            link.setReadTimeout(0)
            return ch
        }

        private fun authData(role: String, transcript: ByteArray) = "starlane-auth-$role".toByteArray() + transcript

        /** Deterministic on both sides: maximise the slower device's cipher throughput. */
        fun chooseSuite(a: Capabilities, b: Capabilities): CipherSuite {
            var best = CipherSuite.AES_128_GCM
            var bestScore = -1
            for (s in CipherSuite.entries) {
                val x = a.cipherBench[s.id] ?: continue
                val y = b.cipherBench[s.id] ?: continue
                val score = minOf(x, y)
                if (score > bestScore) { best = s; bestScore = score }
            }
            return best
        }

        private fun writeRaw(out: OutputStream, b: ByteArray) {
            val hdr = byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte())
            out.write(hdr + b)
            out.flush()
        }

        private fun readRaw(inp: DataInputStream, max: Int): ByteArray {
            val n = try { inp.readInt() } catch (e: EOFException) { throw ProtocolException("peer closed during handshake") }
            if (n <= 0 || n > max) throw ProtocolException("bad handshake length")
            return ByteArray(n).also { inp.readFully(it) }
        }
    }
}
