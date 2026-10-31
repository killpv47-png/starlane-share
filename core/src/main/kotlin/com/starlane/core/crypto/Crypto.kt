package com.starlane.core.crypto

import com.starlane.core.util.Hex
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

val secureRandom: SecureRandom by lazy { SecureRandom() }

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach { md.update(it) }
    return md.digest()
}

fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

private fun ecGenerator(): KeyPairGenerator =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), secureRandom) }

/**
 * Long-term device identity: an ECDSA P-256 key pair. The fingerprint (SHA-256 of the public key)
 * identifies a device; the device id shown in discovery is derived from it, so a peer cannot claim
 * another device's id without owning that device's private key.
 */
class Identity(val keyPair: KeyPair) {
    val publicEncoded: ByteArray = keyPair.public.encoded
    val privateEncoded: ByteArray get() = keyPair.private.encoded
    val fingerprint: String = fingerprintOf(publicEncoded)
    val deviceId: String = deviceIdOf(publicEncoded)

    fun sign(data: ByteArray): ByteArray = Signature.getInstance(SIG_ALG).run {
        initSign(keyPair.private); update(data); sign()
    }

    companion object {
        const val SIG_ALG = "SHA256withECDSA"

        fun generate(): Identity = Identity(ecGenerator().generateKeyPair())

        fun fromEncoded(publicKey: ByteArray, privateKey: ByteArray): Identity {
            val kf = KeyFactory.getInstance("EC")
            return Identity(
                KeyPair(kf.generatePublic(X509EncodedKeySpec(publicKey)), kf.generatePrivate(PKCS8EncodedKeySpec(privateKey)))
            )
        }

        fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance(SIG_ALG).run { initVerify(pub); update(data); verify(signature) }
        } catch (e: Exception) {
            false
        }

        fun fingerprintOf(publicKey: ByteArray): String = Hex.encode(sha256(publicKey), 16)
        fun deviceIdOf(publicKey: ByteArray): String = fingerprintOf(publicKey).substring(0, 16)
    }
}

/** Ephemeral ECDH (P-256) used once per connection for forward secrecy. */
object Ecdh {
    fun generate(): KeyPair = ecGenerator().generateKeyPair()

    fun agree(own: KeyPair, peerPublic: ByteArray): ByteArray {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublic))
        return KeyAgreement.getInstance("ECDH").run { init(own.private); doPhase(pub, true); generateSecret() }
    }
}

/** RFC 5869 HKDF with HMAC-SHA256. */
object Hkdf {
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    fun expand(prk: ByteArray, info: String, length: Int): ByteArray = expand(prk, info.toByteArray(), length)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t); mac.update(info); mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; counter++
        }
        return out
    }

    fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        data.forEach { mac.update(it) }
        return mac.doFinal()
    }
}

/**
 * AEAD suites. Which suite is fastest depends heavily on hardware: AES-GCM is extremely fast on
 * CPUs with AES instructions (most ARMv8 phones); ChaCha20-Poly1305 is usually faster on older
 * ARMv7 devices without them. Both peers benchmark locally and the handshake picks the suite with
 * the best worst-case throughput across the two devices.
 */
enum class CipherSuite(val id: Int, val keyLen: Int, val keyAlg: String, private val names: List<String>) {
    AES_128_GCM(1, 16, "AES", listOf("AES/GCM/NoPadding")),
    CHACHA20_POLY1305(2, 32, "ChaCha20", listOf("ChaCha20/Poly1305/NoPadding", "ChaCha20-Poly1305"));

    fun newCipher(): Cipher {
        var last: Exception? = null
        for (n in names) {
            try {
                return Cipher.getInstance(n)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no cipher")
    }

    fun params(nonce: ByteArray) =
        if (this == AES_128_GCM) GCMParameterSpec(128, nonce) else IvParameterSpec(nonce)

    val isAvailable: Boolean by lazy {
        try {
            val key = ByteArray(keyLen) { 7 }
            val enc = FrameCipher(this, key, ByteArray(4))
            val dec = FrameCipher(this, key, ByteArray(4))
            val pt = ByteArray(64) { it.toByte() }
            val ct = ByteArray(64 + TAG)
            enc.encrypt(null, 0, pt, 0, pt.size, ct, 0)
            val back = ByteArray(64 + TAG)
            dec.decrypt(ct, 0, ct.size, back, 0) == 64 && back.copyOf(64).contentEquals(pt)
        } catch (e: Throwable) {
            false
        }
    }

    companion object {
        const val TAG = 16
        fun byId(id: Int) = entries.firstOrNull { it.id == id }

        /**
         * Measures single-thread encryption throughput (MB/s) of every available suite, spending
         * roughly [budgetMs] per suite. Run once in the background and cache the result.
         */
        fun benchmark(budgetMs: Long = 60): Map<CipherSuite, Int> {
            val res = LinkedHashMap<CipherSuite, Int>()
            val buf = ByteArray(64 * 1024)
            val out = ByteArray(buf.size + TAG)
            for (s in entries) {
                if (!s.isAvailable) continue
                val fc = FrameCipher(s, randomBytes(s.keyLen), randomBytes(4))
                repeat(8) { fc.encrypt(null, 0, buf, 0, buf.size, out, 0) } // warm-up / JIT
                val start = System.nanoTime()
                var bytes = 0L
                while (System.nanoTime() - start < budgetMs * 1_000_000) {
                    fc.encrypt(null, 0, buf, 0, buf.size, out, 0); bytes += buf.size
                }
                val secs = (System.nanoTime() - start) / 1e9
                res[s] = (bytes / secs / 1_000_000).toInt().coerceIn(1, 65_000)
            }
            return res
        }
    }
}

/**
 * Per-direction AEAD with a 96-bit nonce = 4-byte salt || 64-bit frame counter. Counters never
 * repeat for a key, which also gives replay and reorder protection for free: a replayed or
 * reordered frame simply fails authentication.
 */
class FrameCipher(private val suite: CipherSuite, key: ByteArray, salt: ByteArray) {
    private val cipher = suite.newCipher()
    private val keySpec = SecretKeySpec(key, suite.keyAlg)
    private val nonce = ByteArray(12).also { System.arraycopy(salt, 0, it, 0, 4) }
    private var counter = 0L

    private fun nextNonce() {
        val c = counter++
        for (i in 0 until 8) nonce[4 + i] = (c ushr (56 - 8 * i)).toByte()
    }

    /** Encrypts [head] (optional) followed by the payload; returns ciphertext length incl. tag. */
    fun encrypt(head: ByteArray?, headLen: Int, src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        nextNonce()
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, suite.params(nonce))
        var n = 0
        if (head != null && headLen > 0) n += cipher.update(head, 0, headLen, dst, dstOff)
        n += cipher.doFinal(src, off, len, dst, dstOff + n)
        return n
    }

    /** Decrypts and authenticates; throws on any tampering. [dst] needs room for len bytes. */
    fun decrypt(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        nextNonce()
        cipher.init(Cipher.DECRYPT_MODE, keySpec, suite.params(nonce))
        return cipher.doFinal(src, off, len, dst, dstOff)
    }
}
