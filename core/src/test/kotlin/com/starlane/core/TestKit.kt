package com.starlane.core

import com.starlane.core.crypto.CipherSuite
import com.starlane.core.crypto.Identity
import com.starlane.core.engine.ConflictPolicy
import com.starlane.core.engine.Connection
import com.starlane.core.engine.DeviceProfile
import com.starlane.core.engine.FileSystemStorage
import com.starlane.core.engine.IncomingRequest
import com.starlane.core.engine.NodeListener
import com.starlane.core.engine.ResumeStore
import com.starlane.core.engine.Transfer
import com.starlane.core.engine.TransferNode
import com.starlane.core.engine.Tuner
import com.starlane.core.link.LinkKind
import com.starlane.core.link.SocketLink
import com.starlane.core.link.SocketTuning
import com.starlane.core.proto.Profile
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real TCP loopback harness: two complete nodes talking over actual sockets. */
class Peer(
    name: String,
    val root: File,
    val turbo: Boolean = true,
    val device: DeviceProfile = DeviceProfile(cores = 4, lowRam = false, totalRamMb = 4096),
    storageOverride: com.starlane.core.engine.ReceiveStorage? = null,
) : AutoCloseable {
    val identity = Identity.generate()
    val inbox = File(root, "inbox").apply { mkdirs() }
    val storage = storageOverride ?: FileSystemStorage(inbox, ResumeStore(File(root, "resume")))
    val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    @Volatile var autoAccept: Boolean = true
    @Volatile var policy: ConflictPolicy = ConflictPolicy.RENAME
    @Volatile var lastRequest: IncomingRequest? = null
    val requests = java.util.concurrent.LinkedBlockingQueue<IncomingRequest>()
    val changes = java.util.concurrent.LinkedBlockingQueue<Transfer>()
    val connected = java.util.concurrent.LinkedBlockingQueue<Connection>()
    val receiveAsked = java.util.concurrent.LinkedBlockingQueue<Connection>()

    val listener = object : NodeListener {
        override fun onConnected(conn: Connection) { connected.offer(conn) }
        override fun onIncomingRequest(req: IncomingRequest) {
            lastRequest = req
            requests.offer(req)
            if (autoAccept) req.accept(policy)
        }
        override fun onReceiveRequested(conn: Connection) { receiveAsked.offer(conn) }
        override fun onTransferChanged(t: Transfer) { changes.offer(t) }
    }

    val node = TransferNode(
        identity, Profile(name, "$name-phone", "TestModel", 1, "jvm"), storage,
        { Tuner.localCaps(device, turbo, server.localPort, bench) }, listener,
    )

    @Volatile private var running = true

    init {
        node.laneDialer = { conn, tuning ->
            SocketLink.dial("127.0.0.1", conn.peer.caps.listenPort, LinkKind.LOOPBACK, tuning)
        }
        Thread({
            while (running) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                SocketLink.configure(s, SocketTuning.DEFAULT)
                node.io.execute { node.accept(SocketLink(s, LinkKind.LOOPBACK)) }
            }
        }, "accept-$name").apply { isDaemon = true; start() }
    }

    fun connectTo(other: Peer): Connection {
        val link = SocketLink.dial("127.0.0.1", other.server.localPort, LinkKind.LOOPBACK, SocketTuning.DEFAULT)
        return node.connect(link, other.identity.deviceId)
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        node.shutdown()
    }

    companion object {
        val bench: Map<CipherSuite, Int> by lazy { CipherSuite.benchmark(30) }
    }
}

object TestFiles {
    fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    fun random(file: File, size: Long, seed: Long = size): File {
        file.parentFile?.mkdirs()
        val rnd = Random(seed)
        val buf = ByteArray(256 * 1024)
        file.outputStream().buffered(256 * 1024).use { out ->
            var left = size
            while (left > 0) {
                rnd.nextBytes(buf)
                val n = minOf(left, buf.size.toLong()).toInt()
                out.write(buf, 0, n)
                left -= n
            }
        }
        return file
    }

    fun sha(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        file.inputStream().use { inp ->
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return com.starlane.core.util.Hex.encode(md.digest())
    }
}

fun awaitTerminal(t: Transfer, timeoutSec: Long = 120): Transfer {
    val deadline = System.currentTimeMillis() + timeoutSec * 1000
    while (!t.state.terminal && System.currentTimeMillis() < deadline) Thread.sleep(20)
    return t
}

fun awaitState(t: Transfer, timeoutSec: Long = 60, cond: (Transfer) -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutSec * 1000
    while (!cond(t) && System.currentTimeMillis() < deadline) Thread.sleep(10)
    return cond(t)
}

fun CountDownLatch.awaitSec(s: Long) = await(s, TimeUnit.SECONDS)
