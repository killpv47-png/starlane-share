package com.starlane.core.engine

import com.starlane.core.link.LinkKind
import com.starlane.core.proto.Manifest
import com.starlane.core.proto.PeerInfo
import java.util.concurrent.atomic.AtomicLong

enum class Direction { SEND, RECEIVE }

enum class TransferState {
    QUEUED, WAITING_ACCEPT, RUNNING, INTERRUPTED, COMPLETED, FAILED, CANCELLED, REJECTED;

    val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == REJECTED
}

enum class FileStatus { PENDING, DONE, SKIPPED, FAILED }

/** Machine-readable failure reasons (localized by the UI). */
object Reasons {
    const val REJECTED = "rejected"
    const val NO_SPACE = "no_space"
    const val BUSY = "busy"
    const val TIMEOUT = "timeout"
    const val CANCELLED_BY_PEER = "cancelled_by_peer"
    const val CANCELLED = "cancelled"
    const val CONNECTION_LOST = "connection_lost"
    const val INTEGRITY = "integrity"
    const val SOURCE_ERROR = "source_error"
    const val STORAGE_ERROR = "storage_error"
    const val PARTIAL = "partial"
}

/**
 * Observable state of one transfer. Progress counters are lock-free atomics updated by the I/O
 * threads; the UI samples them at a low rate instead of receiving per-chunk callbacks.
 */
class Transfer internal constructor(
    val id: String,
    val direction: Direction,
    val peer: PeerInfo,
    val manifest: Manifest,
    linkKind: LinkKind,
) {
    @Volatile var linkKind: LinkKind = linkKind
        internal set
    @Volatile var state: TransferState = TransferState.QUEUED
        internal set
    @Volatile var reason: String = ""
        internal set
    @Volatile var paused: Boolean = false
        internal set
    @Volatile var tuning: TransferTuning? = null
        internal set
    @Volatile var activeLanes: Int = 0
        internal set
    @Volatile var startedAt: Long = 0
        internal set
    @Volatile var finishedAt: Long = 0
        internal set
    /** Bytes that were already present (resume) or skipped when this run started. */
    @Volatile var bytesAtStart: Long = 0
        internal set
    @Volatile var resumeCount: Int = 0
        internal set

    val bytesDone = AtomicLong()
    val fileStatus: Array<FileStatus> = Array(manifest.files.size) { FileStatus.PENDING }
    val fileLocation: Array<String?> = arrayOfNulls(manifest.files.size)
    val fileError: Array<String?> = arrayOfNulls(manifest.files.size)
    val speed = SpeedMeter()

    val totalBytes: Long = manifest.totalBytes
    val fileCount: Int get() = manifest.files.size

    /** Done bytes clamped to the total (re-sent segments after a lane failure can double count). */
    val doneBytes: Long get() = bytesDone.get().coerceIn(0, totalBytes)

    val progress: Float get() = if (totalBytes == 0L) (if (state == TransferState.COMPLETED) 1f else 0f) else doneBytes.toFloat() / totalBytes

    fun count(s: FileStatus): Int = fileStatus.count { it == s }

    override fun toString() = "Transfer($id $direction ${manifest.files.size} files $state)"
}

/**
 * Speed from real transferred bytes over real elapsed time. The instantaneous rate is smoothed
 * with an exponential moving average to avoid distracting jumps; the average excludes bytes that
 * were resumed or skipped so it reflects genuine throughput.
 */
class SpeedMeter(private val alpha: Double = 0.35) {
    private var lastBytes = -1L
    private var lastNs = 0L
    private var startBytes = 0L
    private var startNs = 0L
    @Volatile var current: Double = 0.0
        private set

    @Synchronized
    fun reset(bytes: Long, nowNs: Long = System.nanoTime()) {
        lastBytes = bytes; lastNs = nowNs; startBytes = bytes; startNs = nowNs; current = 0.0
    }

    /** Call periodically (e.g. every 500 ms). Returns the smoothed bytes/second. */
    @Synchronized
    fun sample(bytes: Long, nowNs: Long = System.nanoTime()): Double {
        if (lastBytes < 0) { reset(bytes, nowNs); return 0.0 }
        val dt = (nowNs - lastNs) / 1e9
        if (dt < 0.05) return current
        val inst = ((bytes - lastBytes).coerceAtLeast(0)) / dt
        current = if (current == 0.0) inst else alpha * inst + (1 - alpha) * current
        lastBytes = bytes; lastNs = nowNs
        return current
    }

    @Synchronized
    fun average(bytes: Long, nowNs: Long = System.nanoTime()): Double {
        if (lastBytes < 0) return 0.0
        val dt = (nowNs - startNs) / 1e9
        return if (dt <= 0.2) current else (bytes - startBytes).coerceAtLeast(0) / dt
    }

    /** Seconds remaining, or -1 when unknown. Blends current and average speed for stability. */
    fun etaSeconds(remaining: Long, bytes: Long, nowNs: Long = System.nanoTime()): Long {
        val avg = average(bytes, nowNs)
        val rate = if (current > 0 && avg > 0) 0.6 * current + 0.4 * avg else maxOf(current, avg)
        if (rate < 1.0) return -1
        return (remaining / rate).toLong()
    }
}
