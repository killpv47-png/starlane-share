package com.starlane.core.engine

import com.starlane.core.link.LinkKind
import com.starlane.core.proto.Manifest
import com.starlane.core.proto.PeerInfo
import com.starlane.core.proto.Response
import com.starlane.core.proto.T
import com.starlane.core.util.ProtocolException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** An authenticated session with one peer, made of one control lane plus optional data lanes. */
class Connection internal constructor(
    val node: TransferNode,
    val sessionId: String,
    val peer: PeerInfo,
    val kind: LinkKind,
    val remoteHost: String?,
    val initiator: Boolean,
) {
    private val lanes = CopyOnWriteArrayList<Lane>()
    internal val sendJobs = ConcurrentHashMap<String, SendJob>()
    internal val recvJobs = ConcurrentHashMap<String, ReceiveJob>()
    val connectedAt = System.currentTimeMillis()

    @Volatile var closed = false
        private set
    @Volatile var closeReason = ""
        private set

    /** Round-trip time of the last ping on the control lane, in ms (-1 = unknown). */
    @Volatile var rttMs = -1L
        private set
    @Volatile private var pingSentAt = 0L

    val laneCount: Int get() = lanes.count { !it.dead }

    /** AEAD suite negotiated for this session (same on all lanes). */
    val cipherSuite: com.starlane.core.crypto.CipherSuite?
        get() = lanes.firstOrNull()?.channel?.suite
    internal fun liveLanes(): List<Lane> = lanes.filter { !it.dead }
    internal val control: Lane? get() = lanes.firstOrNull { it.index == 0 && !it.dead }

    internal fun addLane(index: Int, ch: Channel) {
        val lane = Lane(index, ch)
        if (closed) { ch.close(); return }
        lanes.add(lane)
        lane.startOutbox()
        node.io.execute { readLoop(lane) }
        if (index != 0) sendJobs.values.forEach { it.onLaneAdded(lane) }
    }

    private fun readLoop(lane: Lane) {
        Thread.currentThread().name = "starlane-rx-${lane.index}"
        try {
            while (!lane.dead && !closed) {
                lane.channel.receive { type, b, o, l -> dispatch(lane, type, b, o, l) }
            }
        } catch (e: Exception) {
            // fall through to lane loss handling
        }
        onLaneLost(lane)
    }

    private fun onLaneLost(lane: Lane) {
        if (!lanes.remove(lane)) return
        lane.kill()
        if (lane.index == 0) {
            close(Reasons.CONNECTION_LOST)
        } else {
            recvJobs.values.forEach { it.laneLost(lane) }
            sendJobs.values.forEach { it.laneLost(lane) }
        }
    }

    private fun dispatch(lane: Lane, type: Int, b: ByteArray, o: Int, l: Int) {
        when (type) {
            T.DATA -> {
                val job = lane.rxJob ?: return // data for an aborted segment
                job.onData(lane, b, o, l)
            }
            T.SEG_BEGIN -> {
                val r = Rec.reader(b, o, l)
                val tid = r.str(64)
                val job = recvJobs[tid] ?: throw ProtocolException("unknown transfer")
                job.onSegBegin(lane, r.i32(), r.i32())
            }
            T.SEG_END -> lane.rxJob?.onSegEnd(lane, Rec.reader(b, o, l).raw(32)) ?: lane.resetRx()
            T.SEG_ACK -> {
                val r = Rec.reader(b, o, l)
                sendJobs[r.str(64)]?.onAck(lane, r.i32(), r.i32(), r.bool())
            }
            T.PING -> lane.post(T.PONG)
            T.PONG -> if (pingSentAt > 0) { rttMs = System.currentTimeMillis() - pingSentAt; pingSentAt = 0 }
            T.REQ_SEND -> onSendRequest(lane, b, o, l)
            T.REQ_RECEIVE -> node.io.execute { node.listener.onReceiveRequested(this) }
            T.RESPONSE -> {
                val resp = Response.decode(Rec.reader(b, o, l).raw(l))
                sendJobs[resp.id]?.onResponse(resp)
            }
            T.FILE_END -> {
                val r = Rec.reader(b, o, l)
                recvJobs[r.str(64)]?.onFileEnd(r.i32(), r.raw(32))
            }
            T.FILE_COMMITTED -> {
                val r = Rec.reader(b, o, l)
                sendJobs[r.str(64)]?.onFileCommitted(r.i32(), r.bool(), r.str(256))
            }
            T.SKIP_FILE -> {
                val r = Rec.reader(b, o, l)
                val tid = r.str(64); val f = r.i32(); val why = r.str(256)
                sendJobs[tid]?.onPeerSkip(f, why)
                recvJobs[tid]?.onPeerSkip(f, why)
            }
            T.PAUSE, T.RESUME -> {
                val tid = Rec.reader(b, o, l).str(64)
                val p = type == T.PAUSE
                sendJobs[tid]?.setPausedByPeer(p)
                recvJobs[tid]?.setPausedByPeer(p)
            }
            T.CANCEL -> {
                val r = Rec.reader(b, o, l)
                val tid = r.str(64)
                sendJobs[tid]?.onPeerCancel()
                recvJobs[tid]?.onPeerCancel()
            }
            T.COMPLETE -> recvJobs[Rec.reader(b, o, l).str(64)]?.onComplete()
            T.BYE -> throw ProtocolException("bye")
            else -> Unit // unknown records are ignored for forward compatibility
        }
    }

    private fun onSendRequest(lane: Lane, b: ByteArray, o: Int, l: Int) {
        val req = SendRequest.decode(Rec.reader(b, o, l))
        val manifest = Manifest.decode(req.manifestBytes)
        if (recvJobs.containsKey(manifest.transferId)) throw ProtocolException("duplicate transfer")
        val t = Transfer(manifest.transferId, Direction.RECEIVE, peer, manifest, kind)
        t.tuning = TransferTuning(req.lanes, req.chunk, manifest.segmentSize, com.starlane.core.link.SocketTuning.DEFAULT, req.turbo, req.note)
        val job = ReceiveJob(this, t)
        recvJobs[t.id] = job
        val remembered = if (req.resume) node.acceptedPolicy(t.id) else null
        val free = runCatching { node.storage.freeBytes() }.getOrDefault(-1L)
        val ir = IncomingRequest(this, t, free, req.resume, job)
        job.request = ir
        t.state = TransferState.WAITING_ACCEPT
        node.listener.onTransferChanged(t)
        if (remembered != null) ir.accept(remembered) else node.io.execute { node.listener.onIncomingRequest(ir) }
    }

    /** Starts sending [source] to the peer. Returns immediately; observe the returned transfer. */
    fun send(source: TransferSource, turbo: Boolean, override: TransferTuning? = null): Transfer {
        val tuning = override ?: Tuner.choose(kind, node.caps(), peer.caps, turbo)
        val tid = node.newTransferId()
        val manifest = node.buildManifest(tid, source, tuning.segmentSize)
        return startSend(source, manifest, tuning, turbo, resume = false, previous = null)
    }

    /** Resumes an interrupted outgoing transfer on this (possibly new) connection. */
    fun resume(previous: Transfer, source: TransferSource, turbo: Boolean): Transfer {
        val tuning = Tuner.choose(kind, node.caps(), peer.caps, turbo).copy(segmentSize = previous.manifest.segmentSize)
        return startSend(source, previous.manifest, tuning, turbo, resume = true, previous = previous)
    }

    private fun startSend(source: TransferSource, manifest: Manifest, tuning: TransferTuning, turbo: Boolean, resume: Boolean, previous: Transfer?): Transfer {
        if (closed) throw IllegalStateException("connection closed")
        val t = Transfer(manifest.transferId, Direction.SEND, peer, manifest, kind)
        t.tuning = tuning
        if (previous != null) t.resumeCount = previous.resumeCount + 1
        val job = SendJob(this, source, t, turbo)
        sendJobs[t.id] = job
        job.start(resume)
        return t
    }

    /** Asks the peer to send something to us (the RECEIVE action). */
    fun requestReceive() { control?.post(T.REQ_RECEIVE) }

    fun pause(t: Transfer, paused: Boolean) {
        sendJobs[t.id]?.setPaused(paused) ?: recvJobs[t.id]?.setPaused(paused)
    }

    fun cancel(t: Transfer) {
        sendJobs[t.id]?.cancel() ?: recvJobs[t.id]?.cancel()
    }

    internal fun post(type: Int, w: com.starlane.core.util.WireWriter? = null) { control?.post(type, w) }

    internal fun jobFinished(id: String) {
        sendJobs.remove(id)
        recvJobs.remove(id)
    }

    internal fun tick(now: Long) {
        val c = control ?: return
        if (now - c.channel.lastReceiveAt > node.deadAfterMs && liveLanes().all { now - it.channel.lastReceiveAt > node.deadAfterMs }) {
            close(Reasons.CONNECTION_LOST)
            return
        }
        if (now - c.channel.lastSendAt >= node.pingIntervalMs && pingSentAt == 0L) {
            pingSentAt = now
            c.post(T.PING)
        } else if (pingSentAt != 0L && now - pingSentAt > node.deadAfterMs) {
            pingSentAt = 0
        }
        sendJobs.values.forEach { it.tick(now) }
        recvJobs.values.forEach { it.tick(now) }
    }

    /** Graceful disconnect: tell the peer, then close. */
    fun disconnect() {
        if (closed) return
        val c = control
        if (c != null) {
            c.post(T.BYE)
            node.io.execute {
                runCatching { Thread.sleep(250) }
                close("bye")
            }
        } else {
            close("bye")
        }
    }

    internal fun close(reason: String) {
        synchronized(this) {
            if (closed) return
            closed = true
            closeReason = reason
        }
        lanes.forEach { it.kill() }
        lanes.clear()
        sendJobs.values.toList().forEach { it.connectionLost() }
        recvJobs.values.toList().forEach { it.connectionLost() }
        node.unregister(this, reason)
    }
}
