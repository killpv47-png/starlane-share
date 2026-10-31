package com.starlane.core.engine

import com.starlane.core.crypto.constantTimeEquals
import com.starlane.core.crypto.sha256
import com.starlane.core.proto.Response
import com.starlane.core.proto.T
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sender side of one transfer.
 *
 * Files are split into segments (4-8 MiB). Segments are pulled from a shared queue by one worker
 * per lane, streamed in chunks straight from storage to the encrypted socket (never buffering a
 * whole file), hashed with SHA-256 on the fly and acknowledged by the receiver after it verified
 * the hash. At most [WINDOW] segments per lane are unacknowledged, which bounds memory and gives
 * natural backpressure. Nothing is kept in RAM for retransmission: a failed or rejected segment is
 * simply re-read from storage.
 */
internal class SendJob(
    private val conn: Connection,
    private val source: TransferSource,
    val transfer: Transfer,
    private val turbo: Boolean,
) {
    private val m = transfer.manifest
    private val tid = transfer.id
    private val lock = Object()
    private val queue = ArrayDeque<SegUnit>()
    private val remaining = IntArray(m.files.size)
    private val segHashes = arrayOfNulls<Array<ByteArray?>>(m.files.size)
    private val fileFailed = BooleanArray(m.files.size)
    private val fileEndSent = BooleanArray(m.files.size)
    private val lane0Only = BooleanArray(m.files.size)
    private val retries = HashMap<Long, Int>()
    private val readers = HashMap<Int, SourceReader>()
    private val workerLanes = HashSet<Int>()
    private var finishedFiles = 0

    private val stopped = AtomicBoolean(false)
    @Volatile private var accepted = false
    @Volatile private var pausedLocal = false
    @Volatile private var pausedPeer = false
    private var requestedAt = 0L

    private fun changed() = conn.node.listener.onTransferChanged(transfer)

    fun start(resume: Boolean) {
        val tuning = transfer.tuning!!
        transfer.state = TransferState.WAITING_ACCEPT
        requestedAt = System.currentTimeMillis()
        changed()
        val req = SendRequest(m.encode(), tuning.lanes, tuning.chunkSize, tuning.turbo, tuning.note, resume)
        conn.post(T.REQ_SEND, req.encode())
    }

    fun onResponse(resp: Response) {
        if (stopped.get() || accepted) return
        if (!resp.accepted) {
            finish(TransferState.REJECTED, resp.reason.ifEmpty { Reasons.REJECTED })
            return
        }
        accepted = true
        var already = 0L
        synchronized(lock) {
            val plans = resp.plans.associateBy { it.index }
            for (e in m.files) {
                val plan = plans[e.index]
                val n = m.segmentsOf(e)
                segHashes[e.index] = arrayOfNulls(n)
                if (plan == null || !plan.receive) {
                    transfer.fileStatus[e.index] = FileStatus.SKIPPED
                    fileEndSent[e.index] = true
                    finishedFiles++
                    already += e.size
                    continue
                }
                remaining[e.index] = n
                for (s in 0 until n) {
                    val h = plan.present[s]
                    if (h != null) already += m.segmentLength(e, s).toLong()
                    queue.add(SegUnit(e.index, s, h))
                }
            }
        }
        transfer.bytesDone.set(already)
        transfer.bytesAtStart = already
        transfer.startedAt = System.currentTimeMillis()
        transfer.speed.reset(already)
        transfer.state = TransferState.RUNNING
        changed()
        // Empty files have no segments: finish them right away.
        for (e in m.files) if (!fileEndSent[e.index] && remaining[e.index] == 0) maybeFileEnd(e.index)
        checkAllFinished()
        if (stopped.get()) return

        val tuning = transfer.tuning!!
        conn.liveLanes().take(tuning.lanes).forEach { startWorker(it) }
        val dialer = conn.node.laneDialer
        if (tuning.lanes > 1 && conn.kind.supportsLanes && dialer != null) {
            conn.node.io.execute {
                var next = 1
                while (conn.laneCount < tuning.lanes && next < 8 && !stopped.get()) {
                    val idx = next++
                    if (conn.liveLanes().any { it.index == idx }) continue
                    val link = runCatching { dialer(conn, tuning.socket) }.getOrNull() ?: break
                    if (!conn.node.joinLane(conn, idx, link)) break
                }
            }
        }
    }

    fun onLaneAdded(lane: Lane) {
        if (accepted && !stopped.get() && conn.laneCount <= (transfer.tuning?.lanes ?: 1)) startWorker(lane)
    }

    private fun startWorker(lane: Lane) {
        synchronized(lock) { if (!workerLanes.add(lane.index)) return }
        transfer.activeLanes = workerLanes.size
        conn.node.io.execute { workerLoop(lane) }
    }

    private fun isStopped() = stopped.get()

    private fun workerLoop(lane: Lane) {
        Thread.currentThread().name = "starlane-tx-${lane.index}"
        val tuning = transfer.tuning!!
        val buf = ByteArray(tuning.chunkSize)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            while (!isStopped() && !lane.dead) {
                waitWhilePaused()
                if (!lane.awaitWindow(WINDOW, ::isStopped)) break
                val unit = poll(lane)
                if (unit == null) {
                    if (allQueuedDone()) break
                    Thread.sleep(50)
                    continue
                }
                process(unit, lane, buf, digest)
            }
        } catch (e: InterruptedException) {
            // shutting down
        } catch (e: Exception) {
            lane.kill()
        } finally {
            synchronized(lock) { workerLanes.remove(lane.index) }
            transfer.activeLanes = workerLanes.size
        }
    }

    private fun allQueuedDone(): Boolean = synchronized(lock) { queue.isEmpty() && finishedFiles >= m.files.size }

    private fun poll(lane: Lane): SegUnit? = synchronized(lock) {
        val it = queue.iterator()
        while (it.hasNext()) {
            val u = it.next()
            if (fileFailed[u.file]) { it.remove(); continue }
            if (lane0Only[u.file] && lane.index != 0) continue
            it.remove()
            return u
        }
        null
    }

    private fun readerFor(file: Int): SourceReader = synchronized(lock) {
        readers[file] ?: source.open(file).also { readers[file] = it }
    }

    private fun process(unit: SegUnit, lane: Lane, buf: ByteArray, digest: MessageDigest) {
        val e = m.files[unit.file]
        val reader = try {
            readerFor(unit.file)
        } catch (ex: Exception) {
            failFile(unit.file, Reasons.SOURCE_ERROR); return
        }
        if (!reader.seekable && lane.index != 0) {
            // Sequential sources are always streamed in order by the control-lane worker.
            synchronized(lock) { lane0Only[unit.file] = true; queue.addFirst(unit) }
            return
        }
        val start = unit.seg.toLong() * m.segmentSize
        val len = m.segmentLength(e, unit.seg)

        val verify = unit.verify
        if (verify != null) {
            // Resume: the receiver already has this segment. Confirm it by hashing our copy.
            try {
                digest.reset()
                var p = 0
                while (p < len) {
                    val n = reader.read(start + p, buf, 0, minOf(buf.size, len - p))
                    digest.update(buf, 0, n); p += n
                }
            } catch (ex: Exception) {
                failFile(unit.file, Reasons.SOURCE_ERROR); return
            }
            val h = digest.digest()
            if (constantTimeEquals(h, verify)) {
                segDone(unit.file, unit.seg, h, countBytes = 0) // already counted as resumed
                return
            }
            // Receiver's copy differs from ours: it is no longer "already transferred".
            transfer.bytesDone.addAndGet(-len.toLong())
            unit.verify = null
        }

        synchronized(lane.inflightLock) { lane.inflight.add(unit) }
        unit.sent = 0
        lane.channel.send(T.SEG_BEGIN, Rec.segBegin(tid, unit.file, unit.seg))
        digest.reset()
        var p = 0
        var sourceError = false
        while (p < len) {
            if (isStopped()) return
            if (pausedLocal || pausedPeer) waitWhilePaused()
            val n = try {
                reader.read(start + p, buf, 0, minOf(buf.size, len - p))
            } catch (ex: Exception) {
                sourceError = true; break
            }
            digest.update(buf, 0, n)
            lane.channel.send(T.DATA, buf, 0, n)
            p += n
            unit.sent += n
            transfer.bytesDone.addAndGet(n.toLong())
        }
        if (sourceError) {
            lane.channel.send(T.SEG_END, Rec.segEnd(ByteArray(32)))
            failFile(unit.file, Reasons.SOURCE_ERROR)
            return
        }
        val h = digest.digest()
        unit.hash = h
        lane.channel.send(T.SEG_END, Rec.segEnd(h))
    }

    fun onAck(lane: Lane, file: Int, seg: Int, ok: Boolean) {
        val unit = synchronized(lane.inflightLock) {
            val i = lane.inflight.indexOfFirst { it.file == file && it.seg == seg }
            if (i < 0) null else lane.inflight.removeAt(i).also { lane.inflightLock.notifyAll() }
        } ?: return
        if (fileFailed[file]) return
        if (ok) {
            segDone(file, seg, unit.hash ?: return, countBytes = 0)
        } else {
            transfer.bytesDone.addAndGet(-unit.sent)
            val key = (file.toLong() shl 32) or seg.toLong()
            val n = synchronized(lock) { (retries[key] ?: 0) + 1 }.also { synchronized(lock) { retries[key] = it } }
            if (n > MAX_RETRIES) {
                failFile(file, Reasons.INTEGRITY)
            } else {
                unit.sent = 0
                synchronized(lock) { queue.addFirst(unit) }
            }
        }
    }

    private fun segDone(file: Int, seg: Int, hash: ByteArray, countBytes: Long) {
        if (countBytes > 0) transfer.bytesDone.addAndGet(countBytes)
        val last = synchronized(lock) {
            segHashes[file]!![seg] = hash
            remaining[file]--
            remaining[file] == 0
        }
        if (last) maybeFileEnd(file)
    }

    private fun maybeFileEnd(file: Int) {
        val root = synchronized(lock) {
            if (fileEndSent[file] || fileFailed[file]) return
            fileEndSent[file] = true
            readers.remove(file)?.close()
            rootHash(segHashes[file]!!)
        }
        conn.post(T.FILE_END, Rec.fileEnd(tid, file, root))
    }

    fun onFileCommitted(file: Int, ok: Boolean, reason: String) {
        if (file !in m.files.indices) return
        synchronized(lock) {
            if (transfer.fileStatus[file] != FileStatus.PENDING) return
            transfer.fileStatus[file] = if (ok) FileStatus.DONE else FileStatus.FAILED
            if (!ok) transfer.fileError[file] = reason
            finishedFiles++
        }
        conn.node.listener.onFileFinished(transfer, file)
        checkAllFinished()
    }

    fun onPeerSkip(file: Int, reason: String) {
        if (file in m.files.indices) failFile(file, reason, notifyPeer = false)
    }

    private fun failFile(file: Int, reason: String, notifyPeer: Boolean = true) {
        val first = synchronized(lock) {
            if (fileFailed[file] || transfer.fileStatus[file] != FileStatus.PENDING) return
            fileFailed[file] = true
            queue.removeAll { it.file == file }
            readers.remove(file)?.close()
            transfer.fileStatus[file] = FileStatus.FAILED
            transfer.fileError[file] = reason
            finishedFiles++
            true
        }
        if (first && notifyPeer) conn.post(T.SKIP_FILE, Rec.skipFile(tid, file, reason))
        conn.node.listener.onFileFinished(transfer, file)
        checkAllFinished()
    }

    private fun checkAllFinished() {
        val done = synchronized(lock) { finishedFiles >= m.files.size }
        if (!done) return
        conn.post(T.COMPLETE, Rec.tid(tid))
        val failed = transfer.count(FileStatus.FAILED)
        finish(TransferState.COMPLETED, if (failed > 0) Reasons.PARTIAL else "")
    }

    fun laneLost(lane: Lane) {
        val lost = synchronized(lane.inflightLock) { ArrayList(lane.inflight).also { lane.inflight.clear() } }
        synchronized(lock) {
            for (u in lost.asReversed()) {
                if (fileFailed[u.file]) continue
                transfer.bytesDone.addAndGet(-u.sent)
                u.sent = 0
                queue.addFirst(u)
            }
        }
    }

    private fun waitWhilePaused() {
        synchronized(lock) {
            while ((pausedLocal || pausedPeer) && !stopped.get()) lock.wait(500)
        }
    }

    fun setPaused(p: Boolean) {
        if (transfer.state.terminal) return
        pausedLocal = p
        transfer.paused = pausedLocal || pausedPeer
        conn.post(if (p) T.PAUSE else T.RESUME, Rec.tid(tid))
        synchronized(lock) { lock.notifyAll() }
        changed()
    }

    fun setPausedByPeer(p: Boolean) {
        pausedPeer = p
        transfer.paused = pausedLocal || pausedPeer
        synchronized(lock) { lock.notifyAll() }
        changed()
    }

    fun cancel() {
        if (transfer.state.terminal) return
        conn.post(T.CANCEL, Rec.tidReason(tid, Reasons.CANCELLED))
        finish(TransferState.CANCELLED, Reasons.CANCELLED)
    }

    fun onPeerCancel() = finish(TransferState.CANCELLED, Reasons.CANCELLED_BY_PEER)

    fun connectionLost() {
        if (!transfer.state.terminal) {
            val st = if (accepted || transfer.state == TransferState.WAITING_ACCEPT) TransferState.INTERRUPTED else TransferState.FAILED
            finish(st, Reasons.CONNECTION_LOST)
        }
    }

    fun tick(now: Long) {
        if (!accepted && !stopped.get() && now - requestedAt > conn.node.acceptTimeoutMs + 15_000) {
            conn.post(T.CANCEL, Rec.tidReason(tid, Reasons.TIMEOUT))
            finish(TransferState.FAILED, Reasons.TIMEOUT)
        }
    }

    private fun finish(state: TransferState, reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        synchronized(lock) {
            readers.values.forEach { runCatching { it.close() } }
            readers.clear()
            queue.clear()
            lock.notifyAll()
        }
        conn.liveLanes().forEach { l -> synchronized(l.inflightLock) { l.inflight.clear(); l.inflightLock.notifyAll() } }
        transfer.reason = reason
        transfer.finishedAt = System.currentTimeMillis()
        transfer.state = state
        transfer.activeLanes = 0
        conn.jobFinished(tid)
        changed()
    }

    companion object {
        const val WINDOW = 2
        const val MAX_RETRIES = 3

        /** File-level integrity value: SHA-256 over the ordered SHA-256 hashes of all segments. */
        fun rootHash(segments: Array<ByteArray?>): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            for (h in segments) md.update(h ?: throw IllegalStateException("missing segment hash"))
            return md.digest()
        }

        val EMPTY_ROOT: ByteArray = sha256()
    }
}
