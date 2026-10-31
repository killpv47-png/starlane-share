package com.starlane.share

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starlane.core.crypto.CipherSuite
import com.starlane.core.crypto.Identity
import com.starlane.core.engine.Connection
import com.starlane.core.engine.DeviceProfile
import com.starlane.core.engine.FileListSource
import com.starlane.core.engine.FileStatus
import com.starlane.core.engine.FileSystemStorage
import com.starlane.core.engine.IncomingRequest
import com.starlane.core.engine.NodeListener
import com.starlane.core.engine.ResumeStore
import com.starlane.core.engine.Transfer
import com.starlane.core.engine.TransferNode
import com.starlane.core.engine.TransferState
import com.starlane.core.engine.Tuner
import com.starlane.core.link.LinkKind
import com.starlane.core.link.SocketLink
import com.starlane.core.link.SocketTuning
import com.starlane.core.proto.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Random

/**
 * Runs the real transfer engine on Android's runtime (ART + Conscrypt crypto provider): ECDH/ECDSA
 * handshake, AEAD channel, multi-lane transfer and SHA-256 verification between two nodes over
 * loopback sockets inside one device. Measured numbers are logged with tag "StarlaneBench".
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceEngineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private class Node(name: String, root: File, bench: Map<CipherSuite, Int>) : AutoCloseable {
        val identity = Identity.generate()
        val inbox = File(root, "inbox").apply { mkdirs() }
        val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        val node = TransferNode(
            identity, Profile(name, "", "test", 0, "android"),
            FileSystemStorage(inbox, ResumeStore(File(root, "resume"))),
            { Tuner.localCaps(DeviceProfile(Runtime.getRuntime().availableProcessors(), false, 4096), true, server.localPort, bench) },
            object : NodeListener {
                override fun onIncomingRequest(req: IncomingRequest) = req.accept()
                override fun onConnected(conn: Connection) {}
                override fun onTransferChanged(t: Transfer) {}
            },
        )

        init {
            node.laneDialer = { c, t -> SocketLink.dial("127.0.0.1", c.peer.caps.listenPort, LinkKind.LOOPBACK, t) }
            Thread {
                while (!server.isClosed) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    node.io.execute { node.accept(SocketLink(s, LinkKind.LOOPBACK)) }
                }
            }.apply { isDaemon = true; start() }
        }

        override fun close() { runCatching { server.close() }; node.shutdown() }
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun encryptedMultiLaneTransferIsBitExactOnDevice() {
        val bench = CipherSuite.benchmark(60)
        Log.i("StarlaneBench", "cipher MB/s (single thread, measured on this device): $bench")
        assertTrue("AES-GCM must be available", bench.containsKey(CipherSuite.AES_128_GCM))

        val root = File(ctx.cacheDir, "engine-test").apply { deleteRecursively(); mkdirs() }
        val src = File(root, "src/video.bin").apply { parentFile!!.mkdirs() }
        val rnd = Random(7)
        src.outputStream().use { o -> val b = ByteArray(1 shl 20); repeat(48) { rnd.nextBytes(b); o.write(b) } }

        Node("a", File(root, "a"), bench).use { a ->
            Node("b", File(root, "b"), bench).use { b ->
                val link = SocketLink.dial("127.0.0.1", b.server.localPort, LinkKind.LOOPBACK, SocketTuning.DEFAULT)
                val conn = a.node.connect(link, b.identity.deviceId)
                val t0 = System.nanoTime()
                val t = conn.send(FileListSource(listOf(src to "video.bin")), turbo = true)
                val end = System.currentTimeMillis() + 120_000
                while (!t.state.terminal && System.currentTimeMillis() < end) Thread.sleep(20)
                val secs = (System.nanoTime() - t0) / 1e9
                assertEquals(t.reason, TransferState.COMPLETED, t.state)
                assertEquals(FileStatus.DONE, t.fileStatus[0])
                Thread.sleep(300)
                assertEquals(sha(src), sha(File(b.inbox, "video.bin")))
                Log.i(
                    "StarlaneBench",
                    "on-device loopback transfer: 48 MiB in %.2f s = %.1f MB/s, cipher=%s, lanes=%d, verified=SHA-256"
                        .format(secs, src.length() / secs / 1e6, conn.cipherSuite, t.tuning?.lanes),
                )
            }
        }
        root.deleteRecursively()
    }
}
