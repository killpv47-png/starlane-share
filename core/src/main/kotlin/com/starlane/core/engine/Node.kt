package com.starlane.core.engine

import com.starlane.core.crypto.Identity
import com.starlane.core.crypto.randomBytes
import com.starlane.core.link.Link
import com.starlane.core.link.LinkKind
import com.starlane.core.link.SocketTuning
import com.starlane.core.proto.Capabilities
import com.starlane.core.proto.Manifest
import com.starlane.core.proto.Profile
import com.starlane.core.proto.T
import com.starlane.core.util.Hex
import com.starlane.core.util.ProtocolException
import com.starlane.core.util.WireWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Callbacks from the engine. Invoked on engine threads; implementations must not block for long. */
interface NodeListener {
    fun onConnected(conn: Connection) {}
    fun onDisconnected(conn: Connection, reason: String) {}

    /** A peer wants to send. Call [IncomingRequest.accept] or [IncomingRequest.reject]; times out otherwise. */
    fun onIncomingRequest(req: IncomingRequest) { req.reject() }

    /** The peer chose RECEIVE: it asks this device to pick content and send it. */
    fun onReceiveRequested(conn: Connection) {}

    fun onTransferChanged(t: Transfer) {}
    fun onFileFinished(t: Transfer, index: Int) {}
}

/** Pending incoming transfer awaiting the local user's decision. */
class IncomingRequest internal constructor(
    val connection: Connection,
    val transfer: Transfer,
    val freeBytes: Long,
    val isResume: Boolean,
    private val job: ReceiveJob,
) {
    val createdAt = System.currentTimeMillis()
    private val decided = AtomicInteger(0)

    /**
     * Upper bound of the bytes that must be written if accepted. Resumed segments are only known
     * after storage inspection on accept, so this is conservative (never under-estimates).
     */
    val bytesNeeded: Long get() = transfer.totalBytes
    val isDecided get() = decided.get() != 0

    fun accept(policy: ConflictPolicy = ConflictPolicy.RENAME) {
        if (decided.compareAndSet(0, 1)) connection.node.io.execute { job.accept(policy) }
    }

    fun reject(reason: String = Reasons.REJECTED) {
        if (decided.compareAndSet(0, 2)) job.reject(reason)
    }
}

/**
 * Transport-independent transfer node. Platform code feeds it [Link]s (accepted or dialed) and it
 * handles authentication, sessions, parallel lanes, transfers, verification and resume.
 */
class TransferNode(
    val identity: Identity,
    @Volatile var profile: Profile,
    val storage: ReceiveStorage,
    private val capsProvider: () -> Capabilities,
    @Volatile var listener: NodeListener,
) {
    /** Opens an additional TCP lane to the peer, or returns null when not possible. */
    @Volatile var laneDialer: ((conn: Connection, tuning: SocketTuning) -> Link?)? = null
    @Volatile var acceptTimeoutMs = 60_000L
    @Volatile var pingIntervalMs = 4_000L
    @Volatile var deadAfterMs = 25_000L

    private val counter = AtomicInteger()
    val io: ExecutorService = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "starlane-io-${counter.incrementAndGet()}").apply { isDaemon = true }
    })

    private val sessions = ConcurrentHashMap<String, Connection>()
    /** Transfers the user already accepted; resumptions of them are accepted automatically. */
    private val acceptedPolicies = ConcurrentHashMap<String, ConflictPolicy>()
    @Volatile private var shutdown = false

    private val watchdog = Thread({ watchdogLoop() }, "starlane-watchdog").apply { isDaemon = true; start() }

    fun caps(): Capabilities = capsProvider()

    val connections: List<Connection> get() = sessions.values.filter { !it.closed }

    fun connectionTo(deviceId: String): Connection? = connections.firstOrNull { it.peer.deviceId == deviceId }

    /** Dials-side: handshake as initiator and open a new session. Blocking; call off the main thread. */
    fun connect(link: Link, expectedDeviceId: String? = null): Connection {
        try {
            val ch = Channel.handshake(link, identity, profile, caps(), true, expectedDeviceId)
            val sid = Hex.encode(randomBytes(8))
            ch.send(T.SESSION, WireWriter(32).u8(0).str(sid).u8(0))
            var ok = false
            ch.receive { type, b, o, l ->
                if (type != T.SESSION) throw ProtocolException("expected SESSION")
                ok = Rec.reader(b, o, l).bool()
            }
            if (!ok) throw ProtocolException("session refused")
            val conn = Connection(this, sid, ch.peer, link.kind, link.remoteHost, true)
            register(conn)
            conn.addLane(0, ch)
            return conn
        } catch (e: Exception) {
            link.close()
            throw e
        }
    }

    /** Accept-side: handles one incoming link (new session or extra lane). Blocking. */
    fun accept(link: Link) {
        try {
            val ch = Channel.handshake(link, identity, profile, caps(), false)
            var mode = -1
            var sid = ""
            var laneIdx = 0
            ch.receive { type, b, o, l ->
                if (type != T.SESSION) throw ProtocolException("expected SESSION")
                val r = Rec.reader(b, o, l)
                mode = r.u8(); sid = r.str(64); laneIdx = r.u8()
            }
            when (mode) {
                0 -> {
                    if (sessions.containsKey(sid)) throw ProtocolException("duplicate session")
                    ch.send(T.SESSION, WireWriter(4).bool(true))
                    val conn = Connection(this, sid, ch.peer, link.kind, link.remoteHost, false)
                    register(conn)
                    conn.addLane(0, ch)
                }
                1 -> {
                    val conn = sessions[sid]
                    if (conn == null || conn.closed || conn.peer.deviceId != ch.peer.deviceId || laneIdx !in 1..7) {
                        runCatching { ch.send(T.SESSION, WireWriter(4).bool(false)) }
                        ch.close()
                        return
                    }
                    ch.send(T.SESSION, WireWriter(4).bool(true))
                    conn.addLane(laneIdx, ch)
                }
                else -> throw ProtocolException("bad session mode")
            }
        } catch (e: Exception) {
            link.close()
        }
    }

    /** Joins an extra lane to an existing session (dial side). */
    internal fun joinLane(conn: Connection, index: Int, link: Link): Boolean {
        return try {
            val ch = Channel.handshake(link, identity, profile, caps(), true, conn.peer.deviceId)
            ch.send(T.SESSION, WireWriter(32).u8(1).str(conn.sessionId).u8(index))
            var ok = false
            ch.receive { type, b, o, l -> if (type == T.SESSION) ok = Rec.reader(b, o, l).bool() }
            if (!ok) { ch.close(); return false }
            conn.addLane(index, ch)
            true
        } catch (e: Exception) {
            link.close()
            false
        }
    }

    private fun register(conn: Connection) {
        sessions[conn.sessionId] = conn
        listener.onConnected(conn)
    }

    internal fun unregister(conn: Connection, reason: String) {
        sessions.remove(conn.sessionId)
        listener.onDisconnected(conn, reason)
    }

    internal fun rememberAccepted(tid: String, policy: ConflictPolicy) { acceptedPolicies[tid] = policy }
    internal fun acceptedPolicy(tid: String): ConflictPolicy? = acceptedPolicies[tid]
    fun forgetAccepted(tid: String) { acceptedPolicies.remove(tid) }

    internal fun newTransferId(): String = Hex.encode(randomBytes(10))

    /** Builds the manifest for a source using the segment size selected for the link. */
    internal fun buildManifest(tid: String, source: TransferSource, segmentSize: Int): Manifest =
        Manifest(tid, segmentSize, source.entries, source.groups)

    private fun watchdogLoop() {
        while (!shutdown) {
            try {
                Thread.sleep(minOf(pingIntervalMs, 1000L))
            } catch (e: InterruptedException) {
                return
            }
            val now = System.currentTimeMillis()
            for (c in sessions.values) {
                try {
                    c.tick(now)
                } catch (e: Exception) {
                    // never let one connection break the watchdog
                }
            }
        }
    }

    fun shutdown() {
        shutdown = true
        connections.forEach { it.disconnect() }
        watchdog.interrupt()
        io.shutdownNow()
    }

    companion object {
        fun linkSupportsLanes(kind: LinkKind) = kind.supportsLanes
    }
}
