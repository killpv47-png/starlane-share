package com.starlane.share.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.starlane.core.link.Link
import com.starlane.core.link.LinkKind
import com.starlane.core.link.SocketLink
import com.starlane.core.link.SocketTuning
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.ArrayDeque

/**
 * TCP server shared by LAN and Wi-Fi Direct, plus mDNS/DNS-SD (NSD) advertising and discovery on
 * the local network. Works on the same Wi-Fi network and when one phone's regular hotspot is used.
 */
class LanTransport(
    private val context: Context,
    private val onLink: (Link) -> Unit,
    private val sink: DiscoverySink,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    @Volatile var port: Int = 0
        private set

    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val serviceToId = HashMap<String, String>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var ownServiceName: String? = null

    /** Starts the TCP server (idempotent). Prefers the well-known port so Wi-Fi Direct clients can find it. */
    @Synchronized
    fun startServer(tuning: SocketTuning) {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        if (tuning.receiveBuffer > 0) runCatching { s.receiveBufferSize = tuning.receiveBuffer }
        try {
            s.bind(InetSocketAddress(Txt.DEFAULT_PORT), 50)
        } catch (e: Exception) {
            s.bind(InetSocketAddress(0), 50)
        }
        server = s
        port = s.localPort
        Thread({
            while (!s.isClosed) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                runCatching { SocketLink.configure(sock, tuning) }
                val local = sock.localAddress?.hostAddress ?: ""
                val kind = if (local.startsWith("192.168.49.")) LinkKind.WIFI_DIRECT else LinkKind.LAN
                onLink(SocketLink(sock, kind))
            }
        }, "starlane-accept").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stopServer() {
        runCatching { server?.close() }
        server = null
        port = 0
    }

    fun dial(device: NearbyDevice, tuning: SocketTuning): Link {
        val host = device.lanHost ?: throw IllegalStateException("no LAN address")
        return SocketLink.dial(host, device.lanPort, LinkKind.LAN, tuning)
    }

    fun advertise(txt: Map<String, String>) {
        main.post {
            unregister()
            val info = NsdServiceInfo().apply {
                serviceName = "SL-" + txt["id"]
                serviceType = Txt.SERVICE_TYPE
                port = this@LanTransport.port
                txt.forEach { (k, v) -> runCatching { setAttribute(k, v) } }
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) { ownServiceName = info.serviceName }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
            regListener = l
            runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
        }
    }

    fun unregister() {
        regListener?.let { l -> runCatching { nsd.unregisterService(l) } }
        regListener = null
    }

    fun startDiscovery() {
        main.post {
            if (discListener != null) return@post
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("starlane-mdns").apply { setReferenceCounted(false); runCatching { acquire() } }
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { discListener = null } }
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName == ownServiceName) return
                    main.post { resolveQueue.add(info); pumpResolve() }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post { serviceToId.remove(info.serviceName)?.let { sink.lost(LinkKind.LAN, it) } }
                }
            }
            discListener = l
            runCatching { nsd.discoverServices(Txt.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { discListener = null }
        }
    }

    /** NsdManager can only resolve one service at a time before API 34, so resolves are queued. */
    private fun pumpResolve() {
        if (resolving) return
        val next = resolveQueue.poll() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    main.post { resolving = false; pumpResolve() }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        handleResolved(info)
                        pumpResolve()
                    }
                }
            })
        }.onFailure { resolving = false }
    }

    private fun handleResolved(info: NsdServiceInfo) {
        val attrs = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
        val p = Txt.decode(attrs) ?: return
        @Suppress("DEPRECATION")
        val host: InetAddress = info.host ?: return
        if (host.isLoopbackAddress) return
        serviceToId[info.serviceName] = p.id
        sink.found(NearbyDevice(p.id, p.u, p.n, p.m, p.i, lanHost = host.hostAddress, lanPort = info.port))
    }

    fun stopDiscovery() {
        main.post {
            discListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            discListener = null
            resolveQueue.clear()
            serviceToId.values.toList().forEach { sink.lost(LinkKind.LAN, it) }
            serviceToId.clear()
            runCatching { multicastLock?.release() }
            multicastLock = null
        }
    }
}
