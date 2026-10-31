package com.starlane.share.engine

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service that exists only while a transfer, recovery, session or receive-wait is
 * active. It keeps the process and radios alive during long transfers (required by Android's
 * background execution limits) and stops itself as soon as there is nothing left to do.
 */
class ShareService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifications.ongoing(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifications.ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(Notifications.ID_ONGOING, n)
        } catch (e: Exception) {
            // Starting a foreground service from the background can be refused (API 31+); the
            // transfer still runs while the app is visible.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!needed()) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        if (ShareManager.hasActiveWork()) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "starlane:transfer").apply {
                    setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "starlane:transfer").apply { setReferenceCounted(false); acquire() }
            }
        } else releaseLocks()
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }; wakeLock = null
        runCatching { wifiLock?.release() }; wifiLock = null
    }

    private fun stopSelfSafely() {
        releaseLocks()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile private var running = false

        private fun needed(): Boolean = ShareManager.hasActiveWork() || ShareManager.currentConnection() != null ||
            ShareManager.receiveWaiting.value || ShareManager.incoming.value != null

        /** Starts, refreshes or stops the service depending on current activity. Cheap to call often. */
        fun update(context: Context) {
            val need = needed()
            if (need) {
                if (!running) {
                    running = true
                    val i = Intent(context, ShareService::class.java)
                    runCatching {
                        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
                    }.onFailure { running = false }
                } else {
                    Notifications.refreshOngoing(context)
                }
            } else if (running) {
                running = false
                context.stopService(Intent(context, ShareService::class.java))
                Notifications.cancelOngoing(context)
            }
        }
    }
}

/** Handles notification actions (cancel transfer, disconnect, accept/reject incoming). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { ShareManager.cancel(it) }
            ACTION_CANCEL_ALL -> ShareManager.transfers.value.filter { !it.state.terminal }.forEach { ShareManager.cancel(it.id) }
            ACTION_DISCONNECT -> ShareManager.disconnect()
            ACTION_STOP_RECEIVE -> ShareManager.stopReceiveMode()
            ACTION_REJECT -> ShareManager.incoming.value?.let { ShareManager.reject(it) }
        }
        ShareService.update(context)
    }

    companion object {
        const val ACTION_CANCEL = "com.starlane.share.CANCEL"
        const val ACTION_CANCEL_ALL = "com.starlane.share.CANCEL_ALL"
        const val ACTION_DISCONNECT = "com.starlane.share.DISCONNECT"
        const val ACTION_STOP_RECEIVE = "com.starlane.share.STOP_RECEIVE"
        const val ACTION_REJECT = "com.starlane.share.REJECT"
        const val EXTRA_ID = "id"
    }
}
