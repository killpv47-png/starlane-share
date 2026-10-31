package com.starlane.core.engine

import com.starlane.core.util.WireWriter
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A unit of send work: one segment of one file. */
internal class SegUnit(val file: Int, val seg: Int, @Volatile var verify: ByteArray?) {
    @Volatile var sent = 0L
    @Volatile var hash: ByteArray? = null
}

/**
 * One encrypted channel belonging to a [Connection]. Lane 0 is the control channel; additional
 * lanes carry data in parallel on transports that support multiple sockets.
 *
 * Threading rule that keeps the protocol deadlock-free: reader threads never perform blocking
 * writes. Every record produced while handling an incoming record is posted to the lane's outbox
 * and written by a dedicated writer thread. Only send workers write directly (and block on TCP
 * backpressure), which is safe because the peer's reader threads always keep draining.
 */
class Lane internal constructor(
    val index: Int,
    val channel: Channel,
) {
    private class Out(val type: Int, val body: ByteArray?)

    private val outbox = LinkedBlockingQueue<Out>()

    @Volatile var dead = false
        internal set

    // Receive context for the segment currently arriving on this lane (only touched by its reader).
    internal var rxJob: ReceiveJob? = null
    internal var rxFile = -1
    internal var rxSeg = -1
    internal var rxPos = 0L
    internal var rxStart = 0L
    internal var rxEnd = 0L
    internal var rxIncoming: IncomingFile? = null
    internal val rxDigest: MessageDigest = MessageDigest.getInstance("SHA-256")

    // Sender side: unacknowledged segments sent on this lane (requeued if the lane fails).
    internal val inflight = ArrayList<SegUnit>()
    internal val inflightLock = Object()

    private val outThread = Thread({ outLoop() }, "starlane-out-$index").apply { isDaemon = true }

    internal fun startOutbox() = outThread.start()

    fun post(type: Int, w: WireWriter? = null) {
        if (!dead) outbox.offer(Out(type, w?.toByteArray()))
    }

    private fun outLoop() {
        try {
            while (!dead) {
                val o = outbox.poll(1, TimeUnit.SECONDS) ?: continue
                if (o.body == null) channel.send(o.type) else channel.send(o.type, o.body)
            }
        } catch (e: InterruptedException) {
            // shutting down
        } catch (e: Exception) {
            dead = true
            channel.close()
        }
    }

    internal fun resetRx() {
        rxJob = null; rxFile = -1; rxSeg = -1; rxPos = 0; rxStart = 0; rxEnd = 0; rxIncoming = null
    }

    internal fun kill() {
        dead = true
        channel.close()
        outThread.interrupt()
        synchronized(inflightLock) { inflightLock.notifyAll() }
    }

    /** Waits until fewer than [window] segments are unacknowledged on this lane. */
    internal fun awaitWindow(window: Int, stop: () -> Boolean): Boolean {
        synchronized(inflightLock) {
            while (!dead && !stop() && inflight.size >= window) inflightLock.wait(200)
        }
        return !dead && !stop()
    }
}
