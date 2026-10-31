package com.starlane.share.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.starlane.core.engine.IncomingRequest
import com.starlane.core.engine.Transfer
import com.starlane.core.engine.TransferState
import com.starlane.share.Locales
import com.starlane.share.MainActivity
import com.starlane.share.R
import com.starlane.share.ui.Fmt

/** Plain framework notifications (no extra library). Text uses the in-app language. */
object Notifications {
    const val ID_ONGOING = 1001
    private const val ID_INCOMING = 1002
    private const val ID_DONE = 1003
    private const val CH_TRANSFER = "transfer"
    private const val CH_INCOMING = "incoming"
    private const val CH_DONE = "done"

    private fun ctx(c: Context): Context = Locales.wrap(c)

    private fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val l = ctx(c)
        val m = nm(c)
        m.createNotificationChannel(NotificationChannel(CH_TRANSFER, l.getString(R.string.notif_channel_transfer), NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel(CH_INCOMING, l.getString(R.string.notif_channel_incoming), NotificationManager.IMPORTANCE_HIGH))
        m.createNotificationChannel(NotificationChannel(CH_DONE, l.getString(R.string.notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    @Suppress("DEPRECATION")
    private fun builder(c: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, channel) else Notification.Builder(c)

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun openApp(c: Context, extra: String? = null): PendingIntent {
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (extra != null) i.putExtra("open", extra)
        return PendingIntent.getActivity(c, extra.hashCode(), i, flags())
    }

    private fun action(c: Context, action: String, id: String? = null): PendingIntent {
        val i = Intent(c, ActionReceiver::class.java).setAction(action)
        if (id != null) i.putExtra(ActionReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(c, (action + id).hashCode(), i, flags())
    }

    /** Ongoing notification: progress of the active transfer, or the session/receive state. */
    fun ongoing(c: Context): Notification {
        ensureChannels(c)
        val l = ctx(c)
        val t = ShareManager.transfers.value.firstOrNull { !it.state.terminal }
        val b = builder(c, CH_TRANSFER).setSmallIcon(R.drawable.ic_stat_starlane).setOngoing(true)
            .setOnlyAlertOnce(true).setContentIntent(openApp(c, "transfer"))
        if (t != null) {
            val title = l.getString(if (t.direction == com.starlane.core.engine.Direction.SEND) R.string.notif_sending_to else R.string.notif_receiving_from, t.peerName)
            val text = when {
                t.recovering -> l.getString(R.string.transfer_recovering)
                t.paused -> l.getString(R.string.transfer_paused)
                t.state == TransferState.WAITING_ACCEPT -> l.getString(R.string.transfer_waiting_accept)
                else -> "${Fmt.bytes(l, t.doneBytes)} / ${Fmt.bytes(l, t.totalBytes)} · ${Fmt.speed(l, t.speed)}"
            }
            b.setContentTitle(title).setContentText(text)
                .setProgress(1000, (t.progress * 1000).toInt(), t.state == TransferState.WAITING_ACCEPT)
                .addAction(Notification.Action.Builder(null, l.getString(R.string.action_cancel), action(c, ActionReceiver.ACTION_CANCEL, t.id)).build())
        } else if (ShareManager.currentConnection() != null) {
            val conn = ShareManager.connection.value
            b.setContentTitle(l.getString(R.string.notif_connected_to, conn?.peerName ?: ""))
                .setContentText(l.getString(R.string.notif_session_open))
                .addAction(Notification.Action.Builder(null, l.getString(R.string.action_disconnect), action(c, ActionReceiver.ACTION_DISCONNECT)).build())
        } else {
            b.setContentTitle(l.getString(R.string.notif_waiting_title)).setContentText(l.getString(R.string.notif_waiting_text))
                .addAction(Notification.Action.Builder(null, l.getString(R.string.action_stop), action(c, ActionReceiver.ACTION_STOP_RECEIVE)).build())
        }
        return b.build()
    }

    fun refreshOngoing(c: Context) {
        runCatching { nm(c).notify(ID_ONGOING, ongoing(c)) }
    }

    fun cancelOngoing(c: Context) = nm(c).cancel(ID_ONGOING)

    /** Heads-up incoming request. Accepting always happens inside the app (never silently). */
    fun showIncoming(c: Context, req: IncomingRequest) {
        ensureChannels(c)
        val l = ctx(c)
        val t = req.transfer
        val n = builder(c, CH_INCOMING).setSmallIcon(R.drawable.ic_stat_starlane)
            .setContentTitle(l.getString(R.string.incoming_title, t.peer.profile.username))
            .setContentText(l.resources.getQuantityString(R.plurals.files_count, t.fileCount, t.fileCount) + " · " + Fmt.bytes(l, t.totalBytes))
            .setContentIntent(openApp(c, "incoming")).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(Notification.Action.Builder(null, l.getString(R.string.action_review), openApp(c, "incoming")).build())
            .addAction(Notification.Action.Builder(null, l.getString(R.string.action_reject), action(c, ActionReceiver.ACTION_REJECT)).build())
        if (Build.VERSION.SDK_INT < 26) @Suppress("DEPRECATION") n.setPriority(Notification.PRIORITY_HIGH)
        runCatching { nm(c).notify(ID_INCOMING, n.build()) }
    }

    fun cancelIncoming(c: Context) = nm(c).cancel(ID_INCOMING)

    fun showFinished(c: Context, t: Transfer) {
        if (t.state == TransferState.CANCELLED || t.state == TransferState.REJECTED) return
        ensureChannels(c)
        val l = ctx(c)
        val ok = t.state == TransferState.COMPLETED && t.reason.isEmpty()
        val title = l.getString(if (ok) R.string.notif_done_ok else R.string.notif_done_partial)
        val n = builder(c, CH_DONE).setSmallIcon(R.drawable.ic_stat_starlane).setContentTitle(title)
            .setContentText("${t.peer.profile.username} · ${l.resources.getQuantityString(R.plurals.files_count, t.fileCount, t.fileCount)} · ${Fmt.bytes(l, t.totalBytes)}")
            .setContentIntent(openApp(c, "history")).setAutoCancel(true)
        runCatching { nm(c).notify(ID_DONE, n.build()) }
    }
}
