package com.starlane.core

import com.starlane.core.crypto.CipherSuite
import com.starlane.core.crypto.FrameCipher
import com.starlane.core.crypto.Hkdf
import com.starlane.core.crypto.Identity
import com.starlane.core.crypto.randomBytes
import com.starlane.core.engine.Channel
import com.starlane.core.engine.DeviceProfile
import com.starlane.core.engine.SafeNames
import com.starlane.core.engine.SpeedMeter
import com.starlane.core.engine.Tuner
import com.starlane.core.engine.TurboNote
import com.starlane.core.engine.TurboStatus
import com.starlane.core.link.LinkKind
import com.starlane.core.proto.FileEntry
import com.starlane.core.proto.Manifest
import com.starlane.core.proto.Response
import com.starlane.core.proto.FilePlan
import com.starlane.core.util.Hex
import com.starlane.core.util.ProtocolException
import com.starlane.core.util.WireReader
import com.starlane.core.util.WireWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {
    @Test
    fun bothCipherSuitesRoundTripAndDetectTampering() {
        for (s in CipherSuite.entries.filter { it.isAvailable }) {
            val key = randomBytes(s.keyLen); val salt = randomBytes(4)
            val enc = FrameCipher(s, key, salt); val dec = FrameCipher(s, key, salt)
            val pt = randomBytes(10_000)
            val ct = ByteArray(pt.size + 64)
            val n = enc.encrypt(byteArrayOf(9), 1, pt, 0, pt.size, ct, 0)
            val out = ByteArray(n)
            val m = dec.decrypt(ct, 0, n, out, 0)
            assertEquals(pt.size + 1, m)
            assertArrayEquals(pt, out.copyOfRange(1, m))

            val enc2 = FrameCipher(s, key, salt); val dec2 = FrameCipher(s, key, salt)
            val n2 = enc2.encrypt(null, 0, pt, 0, pt.size, ct, 0)
            ct[5] = (ct[5].toInt() xor 1).toByte()
            try { dec2.decrypt(ct, 0, n2, ByteArray(n2), 0); fail("tampering not detected for $s") } catch (e: Exception) { }
        }
        assertTrue(CipherSuite.AES_128_GCM.isAvailable)
    }

    @Test
    fun replayedFrameIsRejected() {
        val s = CipherSuite.AES_128_GCM
        val key = randomBytes(16); val salt = randomBytes(4)
        val enc = FrameCipher(s, key, salt); val dec = FrameCipher(s, key, salt)
        val ct = ByteArray(64)
        val n = enc.encrypt(null, 0, ByteArray(8), 0, 8, ct, 0)
        dec.decrypt(ct, 0, n, ByteArray(n), 0)
        try { dec.decrypt(ct, 0, n, ByteArray(n), 0); fail("replay accepted") } catch (e: Exception) { }
    }

    @Test
    fun signaturesBindIdentity() {
        val id = Identity.generate()
        val other = Identity.generate()
        val sig = id.sign("hello".toByteArray())
        assertTrue(Identity.verify(id.publicEncoded, "hello".toByteArray(), sig))
        assertFalse(Identity.verify(other.publicEncoded, "hello".toByteArray(), sig))
        assertFalse(Identity.verify(id.publicEncoded, "hellO".toByteArray(), sig))
        val restored = Identity.fromEncoded(id.publicEncoded, id.privateEncoded)
        assertEquals(id.deviceId, restored.deviceId)
        assertEquals(16, id.deviceId.length)
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val ikm = Hex.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = Hex.decode("000102030405060708090a0b0c")
        val info = Hex.decode("f0f1f2f3f4f5f6f7f8f9")
        val okm = Hkdf.expand(Hkdf.extract(salt, ikm), info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hex.encode(okm),
        )
    }
}

class ProtocolTest {
    @Test
    fun manifestRoundTrip() {
        val m = Manifest(
            "tid1", 4 * 1024 * 1024,
            listOf(FileEntry(0, "a/b.txt", 10, "text/plain", key = "k0", meta = mapOf("x" to "y"))),
        )
        val back = Manifest.decode(m.encode())
        assertEquals(m, back)
        assertEquals(1, back.segmentsOf(back.files[0]))
    }

    @Test
    fun responseRoundTrip() {
        val h = randomBytes(32)
        val r = Response("t", true, "", listOf(FilePlan(0, true, mapOf(3 to h)), FilePlan(1, false, emptyMap())), 99)
        val back = Response.decode(r.encode())
        assertEquals(99, back.freeBytes)
        assertArrayEquals(h, back.plans[0].present[3])
        assertFalse(back.plans[1].receive)
    }

    @Test(expected = ProtocolException::class)
    fun truncatedInputIsRejected() {
        val b = WireWriter().str("hello").i64(5).toByteArray()
        WireReader(b.copyOf(b.size - 3)).run { str(); i64() }
    }

    @Test(expected = ProtocolException::class)
    fun hostileSegmentSizeIsRejected() {
        val m = Manifest("t", 4 * 1024 * 1024, emptyList()).encode()
        // Patch the segment size field to 1 byte.
        val idx = 2 + 1
        m[idx] = 0; m[idx + 1] = 0; m[idx + 2] = 0; m[idx + 3] = 1
        Manifest.decode(m)
    }

    @Test
    fun suiteSelectionMaximisesSlowerDevice() {
        val fastAes = com.starlane.core.proto.Capabilities(mapOf(1 to 900, 2 to 300), 8, 4, true, false, 0, 65536)
        val oldArm = com.starlane.core.proto.Capabilities(mapOf(1 to 40, 2 to 120), 4, 2, true, false, 0, 65536)
        assertEquals(CipherSuite.CHACHA20_POLY1305, Channel.chooseSuite(fastAes, oldArm))
        assertEquals(CipherSuite.AES_128_GCM, Channel.chooseSuite(fastAes, fastAes))
    }
}

class SafetyAndTuningTest {
    @Test
    fun sanitisesHostilePaths() {
        assertEquals("etc/passwd", SafeNames.sanitizeRelPath("../../etc/passwd"))
        assertEquals("a/b.txt", SafeNames.sanitizeRelPath("/a//./b.txt"))
        assertEquals("c_/x_y.txt", SafeNames.sanitizeRelPath("c:\\x:y.txt").let { it })
        assertEquals("file", SafeNames.sanitizeRelPath(".."))
        assertEquals("photo (2).jpg", SafeNames.numbered("photo.jpg", 2))
        assertTrue(SafeNames.sanitizeComponent("x".repeat(500) + ".mp4").length <= 120)
        assertTrue(SafeNames.sanitizeComponent("x".repeat(500) + ".mp4").endsWith(".mp4"))
    }

    @Test
    fun turboAdaptsToBothDevices() {
        val bench = mapOf(CipherSuite.AES_128_GCM to 500)
        val strong = Tuner.localCaps(DeviceProfile(8, false, 8192), true, 0, bench)
        val weak = Tuner.localCaps(DeviceProfile(4, true, 1024), true, 0, bench)
        val active = Tuner.choose(LinkKind.WIFI_DIRECT, strong, strong, true)
        assertEquals(TurboStatus.ACTIVE, active.turbo)
        assertTrue(active.lanes > 1)
        val limited = Tuner.choose(LinkKind.WIFI_DIRECT, strong, weak, true)
        assertEquals(TurboStatus.LIMITED, limited.turbo)
        assertEquals(TurboNote.PEER_LOW_END, limited.note)
        assertEquals(1, limited.lanes)
        val local = Tuner.choose(LinkKind.LAN, weak, strong, true)
        assertEquals(TurboStatus.UNAVAILABLE, local.turbo)
        val bt = Tuner.choose(LinkKind.BLUETOOTH, strong, strong, true)
        assertEquals(TurboNote.LINK_BLUETOOTH, bt.note)
        assertEquals(1, bt.lanes)
        assertEquals(TurboStatus.OFF, Tuner.choose(LinkKind.LAN, strong, strong, false).turbo)
    }

    @Test
    fun speedMeterUsesRealBytesAndTime() {
        val m = SpeedMeter(alpha = 1.0)
        m.reset(0, 0)
        val s = m.sample(10_000_000, 1_000_000_000) // 10 MB in 1 s
        assertEquals(10_000_000.0, s, 1.0)
        assertEquals(10_000_000.0, m.average(10_000_000, 1_000_000_000), 1.0)
        assertEquals(9L, m.etaSeconds(90_000_000, 10_000_000, 1_000_000_000))
        val smooth = SpeedMeter(alpha = 0.5)
        smooth.reset(0, 0)
        smooth.sample(10_000_000, 1_000_000_000)
        val v = smooth.sample(10_000_000, 2_000_000_000) // stalled second
        assertEquals(5_000_000.0, v, 1.0)
    }
}
