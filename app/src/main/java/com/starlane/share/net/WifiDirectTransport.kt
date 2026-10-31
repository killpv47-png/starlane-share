package com.starlane.share.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.starlane.core.link.Link
import com.starlane.core.link.LinkKind
import com.starlane.core.link.SocketLink
import com.starlane.core.link.SocketTuning
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wi-Fi Direct: device discovery through Wi-Fi Direct DNS-SD service records (carrying the
 * username, nickname and model in TXT records) and high-throughput direct links that need no
 * router. The group owner always gets 192.168.49.1, so the client simply dials that address on
 * the well-known port; the server side accepts on the shared TCP server.
 */
@SuppressLint("MissingPermission") // Permissions are checked by the caller (Permissions.kt) before use.
class WifiDirectTransport(
    private val context: Context,
    private val sink: DiscoverySink,
) {
    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val main = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var request: WifiP2pDnsSdServiceRequest? = null
    private var localService: WifiP2pDnsSdServiceInfo? = null
    @Volatile private var discovering = false
    @Volatile private var lastInfo: WifiP2pInfo? = null
    @Volatile private var connectedLatch: CountDownLatch? = null
    private val seen = HashMap<String, String>() // mac -> deviceId
    private val refresh = object : Runnable {
        override fun run() {
            if (!discovering) return
            // Wi-Fi Direct service discovery stops on its own after ~2 minutes; re-arm it.
            runDiscovery()
            main.postDelayed(this, 30_000)
        }
    }

    @Volatile var enabled = false
        private set

    val supported: Boolean get() = manager != null &&
        context.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    private fun ensureChannel(): WifiP2pManager.Channel? {
        val m = manager ?: return null
        channel?.let { return it }
        val ch = m.initialize(context, Looper.getMainLooper()) { channel = null }
        channel = ch
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.action) {
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION ->
                            enabled = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                            @Suppress("DEPRECATION")
                            val ni = i.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                            if (ni?.isConnected == true) {
                                m.requestConnectionInfo(ch) { info ->
                                    lastInfo = info
                                    if (info?.groupFormed == true) connectedLatch?.countDown()
                                }
                            } else {
                                lastInfo = null
                            }
                        }
                    }
                }
            }
            val f = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            androidx.core.content.ContextCompat.registerReceiver(context, r, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }
        return ch
    }

    /** Publishes our DNS-SD record so nearby Wi-Fi Direct devices can see username + model. */
    fun advertise(txt: Map<String, String>) {
        val m = manager ?: return
        val ch = ensureChannel() ?: return
        main.post {
            localService?.let { runCatching { m.removeLocalService(ch, it, null) } }
            val info = WifiP2pDnsSdServiceInfo.newInstance("SL-" + txt["id"], Txt.SERVICE_TYPE, txt)
            localService = info
            runCatching { m.addLocalService(ch, info, null) }
        }
    }

    fun startDiscovery() {
        val m = manager ?: return
        val ch = ensureChannel() ?: return
        if (discovering) return
        discovering = true
        m.setDnsSdResponseListeners(
            ch,
            { _, _, _ -> },
            { _, txt, device ->
                val p = Txt.decode(txt ?: emptyMap()) ?: return@setDnsSdResponseListeners
                seen[device.deviceAddress] = p.id
                val model = p.m.ifBlank { device.deviceName ?: "" }
                sink.found(NearbyDevice(p.id, p.u, p.n, model, p.i, p2pAddress = device.deviceAddress, p2pPort = p.port))
            },
        )
        main.post(refresh)
    }

    private fun runDiscovery() {
        val m = manager ?: return
        val ch = channel ?: return
        request?.let { runCatching { m.removeServiceRequest(ch, it, null) } }
        val req = WifiP2pDnsSdServiceRequest.newInstance(Txt.SERVICE_TYPE)
        request = req
        runCatching {
            m.addServiceRequest(ch, req, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { runCatching { m.discoverServices(ch, null) } }
                override fun onFailure(reason: Int) {}
            })
        }
    }

    fun stopDiscovery() {
        discovering = false
        main.removeCallbacks(refresh)
        val m = manager ?: return
        val ch = channel ?: return
        request?.let { runCatching { m.removeServiceRequest(ch, it, null) } }
        request = null
        runCatching { m.stopPeerDiscovery(ch, null) }
        seen.values.toList().forEach { sink.lost(LinkKind.WIFI_DIRECT, it) }
        seen.clear()
    }

    /**
     * Forms a Wi-Fi Direct group with the device and returns a socket link. Blocking (up to ~30 s,
     * the other phone may show a system confirmation dialog). Must be called off the main thread.
     */
    fun connect(device: NearbyDevice, tuning: SocketTuning): Link {
        val m = manager ?: throw IllegalStateException("Wi-Fi Direct unsupported")
        val ch = ensureChannel() ?: throw IllegalStateException("Wi-Fi Direct unavailable")
        val mac = device.p2pAddress ?: throw IllegalStateException("no Wi-Fi Direct address")
        val latch = CountDownLatch(1)
        connectedLatch = latch
        val info0 = lastInfo
        if (info0?.groupFormed != true) {
            val cfg = WifiP2pConfig().apply {
                deviceAddress = mac
                @Suppress("DEPRECATION")
                wps.setup = WpsInfo.PBC
                // Let the peer be group owner; we dial it. (0 = least inclination to be GO.)
                groupOwnerIntent = 0
            }
            val failed = arrayOf<Int?>(null)
            val sent = CountDownLatch(1)
            main.post {
                m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { sent.countDown() }
                    override fun onFailure(reason: Int) { failed[0] = reason; sent.countDown(); latch.countDown() }
                })
            }
            sent.await(10, TimeUnit.SECONDS)
            if (failed[0] != null) throw IllegalStateException("Wi-Fi Direct connect failed (${failed[0]})")
            if (!latch.await(35, TimeUnit.SECONDS)) {
                main.post { runCatching { m.cancelConnect(ch, null) } }
                throw IllegalStateException("Wi-Fi Direct group not formed in time")
            }
        }
        val info = lastInfo ?: throw IllegalStateException("no group info")
        if (info.isGroupOwner) {
            // Unusual: we became owner anyway. The peer will dial us; wait for its incoming link.
            throw GroupOwnerException()
        }
        val host = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
        var last: Exception? = null
        repeat(10) {
            try {
                return SocketLink.dial(host, device.p2pPort.takeIf { it > 0 } ?: Txt.DEFAULT_PORT, LinkKind.WIFI_DIRECT, tuning, 4000)
            } catch (e: Exception) {
                last = e
                Thread.sleep(700) // the peer's server may need a moment after the group forms
            }
        }
        throw last ?: IllegalStateException("could not reach peer")
    }

    /** Thrown when this phone ended up as group owner; the session then arrives as an incoming link. */
    class GroupOwnerException : Exception("became group owner; waiting for peer")

    fun removeGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        main.post { runCatching { m.removeGroup(ch, null) } }
        lastInfo = null
    }

    fun release() {
        stopDiscovery()
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            localService?.let { runCatching { m.removeLocalService(ch, it, null) } }
            runCatching { m.clearLocalServices(ch, null) }
        }
        localService = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        if (Build.VERSION.SDK_INT >= 27) runCatching { ch?.close() }
        channel = null
    }
}
