package com.starlane.core.link

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

enum class LinkKind(val supportsLanes: Boolean) {
    LAN(true), WIFI_DIRECT(true), HOTSPOT(true), BLUETOOTH(false), USB(false), LOOPBACK(true)
}

/**
 * A raw, bidirectional byte pipe produced by a transport (TCP socket, RFCOMM socket, USB accessory
 * or USB host bulk endpoints). Everything above this level is transport-independent.
 */
interface Link : Closeable {
    val kind: LinkKind
    val input: InputStream
    val output: OutputStream
    val description: String

    /** Enables a read timeout during the handshake where the transport supports it. */
    fun setReadTimeout(ms: Int) {}

    /** Remote address for transports that can open additional parallel lanes. */
    val remoteHost: String? get() = null
}

/** Socket options are chosen per device class / Turbo Mode rather than hard-coded. */
data class SocketTuning(val sendBuffer: Int, val receiveBuffer: Int) {
    companion object {
        val DEFAULT = SocketTuning(0, 0) // keep OS auto-tuning
        val TURBO = SocketTuning(2 * 1024 * 1024, 2 * 1024 * 1024)
        val LOW_RAM = SocketTuning(256 * 1024, 256 * 1024)
    }
}

class SocketLink(
    private val socket: Socket,
    override val kind: LinkKind,
) : Link {
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()
    override val description: String = "${kind.name} ${socket.inetAddress?.hostAddress}:${socket.port}"
    override val remoteHost: String? = socket.inetAddress?.hostAddress

    override fun setReadTimeout(ms: Int) {
        runCatching { socket.soTimeout = ms }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        fun configure(s: Socket, tuning: SocketTuning) {
            s.tcpNoDelay = true
            s.keepAlive = true
            if (tuning.sendBuffer > 0) runCatching { s.sendBufferSize = tuning.sendBuffer }
            if (tuning.receiveBuffer > 0) runCatching { s.receiveBufferSize = tuning.receiveBuffer }
            // Prefer bandwidth over latency (IPTOS_THROUGHPUT); ignored where unsupported.
            runCatching { s.trafficClass = 0x08 }
        }

        fun dial(host: String, port: Int, kind: LinkKind, tuning: SocketTuning, timeoutMs: Int = 8000): SocketLink {
            val s = Socket()
            configure(s, tuning)
            try {
                s.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (e: Exception) {
                runCatching { s.close() }
                throw e
            }
            return SocketLink(s, kind)
        }
    }
}

/** Generic stream-backed link (Bluetooth RFCOMM, USB accessory, USB host adapter). */
class StreamLink(
    override val kind: LinkKind,
    override val input: InputStream,
    override val output: OutputStream,
    override val description: String,
    private val onClose: () -> Unit,
) : Link {
    @Volatile private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { onClose() }
    }
}
