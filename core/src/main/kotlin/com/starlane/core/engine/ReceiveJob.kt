package com.starlane.core.engine

import com.starlane.core.crypto.constantTimeEquals
import com.starlane.core.proto.FilePlan
import com.starlane.core.proto.Response
import com.starlane.core.proto.T
import com.starlane.core.util.ProtocolException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receiver side of one transfer. Data is written positionally straight to a partial file as it
 * arrives (no whole-file buffering); every segment is hashed while being written and acknowledged
 * only when its SHA-256 matches. Verified segment hashes are checkpointed so an interrupted
 * transfer resumes from the last verified segment, and the file-level root hash is checked before
 * the partial file is committed to its final name.
 */
internal class ReceiveJob(
    private val conn: Connection,
    val transfer: Transfer,
) {
    private val m = transfer.manifest
    private val tid = transfer.id
    private val lock = Object()
    @Volatile var request: IncomingRequest? = null

    private lateinit var ctx: IncomingContext
    private val incoming = arrayOfNulls<IncomingFile>(m.files.size)
    private val segHashes = arrayOfNulls<Array<ByteArray?>>(m.files.size)
    private val expected = BooleanArray(m.files.size)
    private val pendingRoot = arrayOfNulls<ByteArray>(m.files.size)
    private val stopped = AtomicBoolean(false)
    @Volatile private var accepted = false
    @Volatile private var pausedLocal = false
    @Volatile private var pausedPeer = false
    private var lastCheckpoint = 0L
    private val createdAt = System.currentTimeMillis()

    private fun changed() = conn.node.listener.onTransferChanged(transfer)

    fun accept(policy: ConflictPolicy) {
        if (stopped.get()) return
        val storage = conn.node.storage
        ctx = IncomingContext(conn.peer.deviceId, m.segmentSize, policy)
        val plans = ArrayList<FilePlan>(m.files.size)
        var needed = 0L
        var already = 0L
        try {
            for (e in m.files) {
                val n = m.segmentsOf(e)
                segHashes[e.index] = arrayOfNulls(n)
                when (val p = storage.prepare(ctx, e)) {
                    is Prepared.Skip -> {
                        transfer.fileStatus[e.index] = FileStatus.SKIPPED
                        transfer.fileLocation[e.index] = p.existing
                        plans += FilePlan(e.index, false, emptyMap())
                        already += e.size
                    }
                    is Prepared.Receive -> {
                        val present = p.present.filterKeys { it in 0 until n }
                        present.forEach { (s, h) -> segHashes[e.index]!![s] = h }
                        val presentBytes = present.keys.sumOf { m.segmentLength(e, it).toLong() }
                        needed += e.size - presentBytes
                        already += presentBytes
                        incoming[e.index] = storage.open(ctx, e, Prepared.Receive(p.resumeKey, present))
                        expected[e.index] = true
                        plans += FilePlan(e.index, true, present)
                    }
                }
            }
        } catch (ex: Exception) {
            reject(Reasons.STORAGE_ERROR)
            return
        }
        val free = runCatching { storage.freeBytes() }.getOrDefault(-1L)
        // Keep a 32 MiB safety margin so the device is never filled completely.
        if (free >= 0 && needed + SAFETY_MARGIN > free) {
            reject(Reasons.NO_SPACE)
            return
        }
        conn.node.rememberAccepted(tid, policy)
        accepted = true
        transfer.bytesDone.set(already)
        transfer.bytesAtStart = already
        transfer.startedAt = System.currentTimeMillis()
        transfer.speed.reset(already)
        transfer.state = TransferState.RUNNING
        conn.post(T.RESPONSE, com.starlane.core.util.WireWriter().raw(Response(tid, true, "", plans, free).encode()))
        changed()
    }

    fun reject(reason: String) {
        if (accepted) return
        conn.post(T.RESPONSE, com.starlane.core.util.WireWriter().raw(Response(tid, false, reason).encode()))
        closeFiles(keepForResume = true)
        finish(TransferState.REJECTED, reason)
    }

    fun onSegBegin(lane: Lane, file: Int, seg: Int) {
        if (!accepted || stopped.get()) throw ProtocolException("unexpected segment")
        if (file !in m.files.indices) throw ProtocolException("bad file index")
        val e = m.files[file]
        if (seg < 0 || seg >= m.segmentsOf(e)) throw ProtocolException("bad segment index")
        val inc = incoming[file]
        if (inc == null || !expected[file]) {
            // File was skipped or failed meanwhile: swallow the data and NACK it.
            lane.rxJob = this; lane.rxFile = file; lane.rxSeg = seg; lane.rxIncoming = null
            lane.rxStart = 0; lane.rxPos = 0; lane.rxEnd = m.segmentLength(e, seg).toLong()
            return
        }
        val wasPresent = synchronized(lock) {
            val arr = segHashes[file]!!
            (arr[seg] != null).also { if (it) arr[seg] = null }
        }
        if (wasPresent) {
            // Sender found our resumed copy invalid and re-sends it: uncount it.
            transfer.bytesDone.addAndGet(-m.segmentLength(e, seg).toLong())
            inc.segmentInvalid(seg)
        }
        lane.rxJob = this
        lane.rxFile = file
        lane.rxSeg = seg
        lane.rxIncoming = inc
        lane.rxStart = seg.toLong() * m.segmentSize
        lane.rxPos = lane.rxStart
        lane.rxEnd = lane.rxStart + m.segmentLength(e, seg)
        lane.rxDigest.reset()
    }

    fun onData(lane: Lane, b: ByteArray, o: Int, l: Int) {
        if (lane.rxPos + l > lane.rxEnd) throw ProtocolException("segment overflow")
        val inc = lane.rxIncoming
        if (inc != null) {
            try {
                inc.write(lane.rxPos, b, o, l)
            } catch (ex: Exception) {
                storageFailed(lane.rxFile)
                lane.rxIncoming = null
            }
            lane.rxDigest.update(b, o, l)
            transfer.bytesDone.addAndGet(l.toLong())
        }
        lane.rxPos += l
    }

    fun onSegEnd(lane: Lane, hash: ByteArray) {
        val file = lane.rxFile
        val seg = lane.rxSeg
        val inc = lane.rxIncoming
        val complete = lane.rxPos == lane.rxEnd
        val ok = inc != null && complete && constantTimeEquals(lane.rxDigest.digest(), hash)
        if (inc != null) {
            if (ok) {
                synchronized(lock) { segHashes[file]!![seg] = hash }
                inc.segmentVerified(seg, hash)
            } else {
                transfer.bytesDone.addAndGet(-(lane.rxPos - lane.rxStart))
                inc.segmentInvalid(seg)
            }
        }
        lane.resetRx()
        lane.post(T.SEG_ACK, Rec.segAck(tid, file, seg, ok))
        maybeCommit(file)
    }

    fun onFileEnd(file: Int, root: ByteArray) {
        if (file !in m.files.indices) return
        synchronized(lock) { pendingRoot[file] = root }
        maybeCommit(file)
    }

    /** Commits once the root hash arrived and every segment hash is known. */
    private fun maybeCommit(file: Int) {
        val root: ByteArray
        val hashes: Array<ByteArray?>
        synchronized(lock) {
            root = pendingRoot[file] ?: return
            hashes = segHashes[file]!!
            if (hashes.any { it == null } || !expected[file]) return
            pendingRoot[file] = null
            expected[file] = false
        }
        val inc = incoming[file] ?: return
        incoming[file] = null
        val e = m.files[file]
        val actual = if (hashes.isEmpty()) SendJob.EMPTY_ROOT else SendJob.rootHash(hashes)
        // Commit on an I/O thread so the reader keeps draining the socket.
        conn.node.io.execute {
            var ok = false
            var reason = ""
            try {
                if (!constantTimeEquals(actual, root)) {
                    reason = Reasons.INTEGRITY
                    inc.abort(keepForResume = false)
                } else {
                    transfer.fileLocation[file] = inc.commit()
                    ok = true
                }
            } catch (ex: Exception) {
                reason = Reasons.STORAGE_ERROR
                runCatching { inc.abort(false) }
            }
            synchronized(lock) {
                transfer.fileStatus[file] = if (ok) FileStatus.DONE else FileStatus.FAILED
                if (!ok) transfer.fileError[file] = reason
            }
            conn.post(T.FILE_COMMITTED, Rec.fileCommitted(tid, file, ok, reason))
            conn.node.listener.onFileFinished(transfer, file)
            checkDone()
        }
    }

    private fun storageFailed(file: Int) {
        val inc = synchronized(lock) {
            if (!expected[file]) return
            expected[file] = false
            incoming[file].also { incoming[file] = null }
        }
        runCatching { inc?.abort(false) }
        transfer.fileStatus[file] = FileStatus.FAILED
        transfer.fileError[file] = Reasons.STORAGE_ERROR
        conn.post(T.SKIP_FILE, Rec.skipFile(tid, file, Reasons.STORAGE_ERROR))
        conn.node.listener.onFileFinished(transfer, file)
        checkDone()
    }

    fun onPeerSkip(file: Int, reason: String) {
        if (file !in m.files.indices) return
        val inc = synchronized(lock) {
            if (!expected[file]) return
            expected[file] = false
            incoming[file].also { incoming[file] = null }
        }
        runCatching { inc?.abort(false) }
        transfer.fileStatus[file] = FileStatus.FAILED
        transfer.fileError[file] = reason
        conn.node.listener.onFileFinished(transfer, file)
        checkDone()
    }

    fun onComplete() = checkDone(senderComplete = true)

    private fun checkDone(senderComplete: Boolean = false) {
        val allDone = transfer.fileStatus.none { it == FileStatus.PENDING }
        if (!allDone) {
            if (senderComplete) {
                // Sender considers all files finished; anything still pending here failed.
                for (i in m.files.indices) if (transfer.fileStatus[i] == FileStatus.PENDING) onPeerSkip(i, Reasons.SOURCE_ERROR)
            }
            return
        }
        conn.node.forgetAccepted(tid)
        val failed = transfer.count(FileStatus.FAILED)
        finish(TransferState.COMPLETED, if (failed > 0) Reasons.PARTIAL else "")
    }

    fun laneLost(lane: Lane) {
        if (lane.rxJob === this) {
            transfer.bytesDone.addAndGet(-(lane.rxPos - lane.rxStart))
            lane.resetRx()
        }
    }

    fun setPaused(p: Boolean) {
        if (transfer.state.terminal) return
        pausedLocal = p
        transfer.paused = pausedLocal || pausedPeer
        conn.post(if (p) T.PAUSE else T.RESUME, Rec.tid(tid))
        changed()
    }

    fun setPausedByPeer(p: Boolean) {
        pausedPeer = p
        transfer.paused = pausedLocal || pausedPeer
        changed()
    }

    fun cancel() {
        if (transfer.state.terminal) return
        request?.let { if (!it.isDecided) { it.reject(Reasons.REJECTED); return } }
        conn.post(T.CANCEL, Rec.tidReason(tid, Reasons.CANCELLED))
        conn.node.forgetAccepted(tid)
        closeFiles(keepForResume = false)
        finish(TransferState.CANCELLED, Reasons.CANCELLED)
    }

    fun onPeerCancel() {
        conn.node.forgetAccepted(tid)
        closeFiles(keepForResume = false)
        finish(TransferState.CANCELLED, Reasons.CANCELLED_BY_PEER)
    }

    fun connectionLost() {
        if (transfer.state.terminal) return
        closeFiles(keepForResume = true)
        finish(if (accepted) TransferState.INTERRUPTED else TransferState.FAILED, Reasons.CONNECTION_LOST)
    }

    fun tick(now: Long) {
        if (accepted && now - lastCheckpoint > CHECKPOINT_MS) {
            lastCheckpoint = now
            incoming.forEach { f -> runCatching { f?.checkpoint() } }
        }
        val req = request
        if (!accepted && req != null && !req.isDecided && now - createdAt > conn.node.acceptTimeoutMs) {
            req.reject(Reasons.TIMEOUT)
        }
    }

    private fun closeFiles(keepForResume: Boolean) {
        for (i in incoming.indices) {
            val f = incoming[i] ?: continue
            incoming[i] = null
            runCatching { f.abort(keepForResume) }
        }
    }

    private fun finish(state: TransferState, reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        transfer.reason = reason
        transfer.finishedAt = System.currentTimeMillis()
        transfer.state = state
        conn.jobFinished(tid)
        changed()
    }

    companion object {
        const val SAFETY_MARGIN = 32L * 1024 * 1024
        const val CHECKPOINT_MS = 2_000L
    }
}
