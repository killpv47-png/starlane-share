package com.starlane.share.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.starlane.core.crypto.CipherSuite
import com.starlane.core.engine.Connection
import com.starlane.core.engine.DeviceProfile
import com.starlane.core.engine.Direction
import com.starlane.core.engine.FileStatus
import com.starlane.core.engine.IncomingRequest
import com.starlane.core.engine.NodeListener
import com.starlane.core.engine.Reasons
import com.starlane.core.engine.ResumeStore
import com.starlane.core.engine.Transfer
import com.starlane.core.engine.TransferNode
import com.starlane.core.engine.TransferState
import com.starlane.core.engine.Tuner
import com.starlane.core.link.Link
import com.starlane.core.link.LinkKind
import com.starlane.core.link.SocketLink
import com.starlane.core.link.SocketTuning
import com.starlane.core.proto.GroupInfo
import com.starlane.core.proto.Profile
import com.starlane.share.App
import com.starlane.share.Prefs
import com.starlane.share.data.DeviceStore
import com.starlane.share.data.HistoryEntry
import com.starlane.share.data.HistoryStore
import com.starlane.share.data.IdentityStore
import com.starlane.share.files.AndroidStorage
import com.starlane.share.files.PickItem
import com.starlane.share.files.PickSource
import com.starlane.share.net.BluetoothTransport
import com.starlane.share.net.DiscoverySink
import com.starlane.share.net.LanTransport
import com.starlane.share.net.NearbyDevice
import com.starlane.share.net.Txt
import com.starlane.share.net.UsbTransport
import com.starlane.share.net.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-wide coordinator between the transport-independent engine and Android: transports,
 * discovery, sessions, transfers, recovery, history, notifications and UI state.
 *
 * Nothing heavy happens until the user scans, becomes visible or connects ([ensureStarted]).
 */
object ShareManager {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs: Prefs get() = App.instance.prefs

    // ---- UI state -------------------------------------------------------------------------
    val nearby = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val scanning = MutableStateFlow(false)
    val visible = MutableStateFlow(false)
    val connection = MutableStateFlow<ConnUi?>(null)
    val connecting = MutableStateFlow<ConnectingUi?>(null)
    val incoming = MutableStateFlow<IncomingRequest?>(null)
    val transfers = MutableStateFlow<List<TransferUi>>(emptyList())
    val usbStatus = MutableStateFlow(UsbTransport.Status.NOTHING_CONNECTED)
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events

    // ---- Engine ----------------------------------------------------------------------------
    @Volatile private var node: TransferNode? = null
    private lateinit var lan: LanTransport
    private lateinit var wifiDirect: WifiDirectTransport
    private lateinit var bluetooth: BluetoothTransport
    lateinit var usb: UsbTransport
        private set
    val history by lazy { HistoryStore(app) }
    val devices by lazy { DeviceStore(app) }

    private val live = ConcurrentHashMap<String, Transfer>()
    private val order = ArrayList<String>()
    private val sources = ConcurrentHashMap<String, PickSource>()
    private val recovering = ConcurrentHashMap.newKeySet<String>()
    private var tickerJob: Job? = null
    @Volatile private var lastDevice: NearbyDevice? = null
    @Volatile var deviceProfile: DeviceProfile = DeviceProfile(Runtime.getRuntime().availableProcessors(), false, 0)
        private set
    @Volatile private var bench: Map<CipherSuite, Int> = emptyMap()

    fun init(context: Context) {
        app = context.applicationContext
    }

    val isStarted: Boolean get() = node != null

    /** Lazily creates identity, storage, engine and transports. Safe to call repeatedly; blocking. */
    @Synchronized
    fun ensureStarted(): TransferNode {
        node?.let { return it }
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        deviceProfile = DeviceProfile(Runtime.getRuntime().availableProcessors(), am.isLowRamDevice, mi.totalMem / (1024 * 1024), Build.VERSION.SDK_INT)
        bench = loadBench()
        val identity = IdentityStore.load(app)
        val storage = AndroidStorage(app, prefs, ResumeStore(File(app.filesDir, "resume"))) { _, _ -> }
        scope.launch(Dispatchers.IO) { runCatching { storage.cleanupStale(7L * 24 * 3600 * 1000) } }
        val sink = object : DiscoverySink {
            override fun found(d: NearbyDevice) = onFound(d)
            override fun lost(kind: LinkKind, deviceId: String) = onLost(kind, deviceId)
        }
        lan = LanTransport(app, ::onIncomingLink, sink)
        wifiDirect = WifiDirectTransport(app, sink)
        bluetooth = BluetoothTransport(app, ::onIncomingLink, sink)
        usb = UsbTransport(app) { link ->
            if (link is UsbTransport.UsbHostLink) scope.launch(Dispatchers.IO) { connectLink(link, null, PeerAction.NONE) }
            else onIncomingLink(link)
        }
        usb.onStatus = { usbStatus.value = it }
        val n = TransferNode(identity, profile(), storage, { caps() }, Listener)
        n.laneDialer = { conn, tuning ->
            val host = conn.remoteHost
            val port = conn.peer.caps.listenPort
            if (host != null && port > 0) SocketLink.dial(host, port, conn.kind, tuning, 5000) else null
        }
        node = n
        lan.startServer(socketTuning())
        return n
    }

    private fun socketTuning(): SocketTuning =
        if (deviceProfile.lowEnd) SocketTuning.LOW_RAM else if (prefs.turbo) SocketTuning.TURBO else SocketTuning.DEFAULT

    private fun caps() = Tuner.localCaps(deviceProfile, prefs.turbo, if (::lan.isInitialized) lan.port else 0, bench)

    fun profile() = Profile(prefs.username, prefs.nickname, deviceModel(), prefs.icon, "Android ${Build.VERSION.RELEASE}")

    fun deviceModel(): String {
        val m = Build.MODEL ?: ""
        val b = Build.MANUFACTURER ?: ""
        return if (m.startsWith(b, ignoreCase = true)) m else "${b.replaceFirstChar { it.uppercase() }} $m".trim()
    }

    /** Cipher benchmark is measured once per app version (~0.15 s) and cached. */
    private fun loadBench(): Map<CipherSuite, Int> {
        val tag = "v${Build.VERSION.SDK_INT}:"
        val cached = prefs.cipherBench
        if (cached.startsWith(tag)) {
            val m = cached.removePrefix(tag).split(',').mapNotNull { p ->
                val kv = p.split(':'); CipherSuite.byId(kv.getOrNull(0)?.toIntOrNull() ?: -1)?.let { it to (kv.getOrNull(1)?.toIntOrNull() ?: 1) }
            }.toMap()
            if (m.isNotEmpty()) return m
        }
        val b = CipherSuite.benchmark(if (deviceProfile.lowEnd) 40 else 60)
        prefs.cipherBench = tag + b.entries.joinToString(",") { "${it.key.id}:${it.value}" }
        return b
    }

    /** Re-publishes the profile after the user edits username/nickname/icon. */
    fun profileChanged() {
        val n = node ?: return
        n.profile = profile()
        if (visible.value || scanning.value) advertise()
    }

    // ---- Discovery & visibility ------------------------------------------------------------
    private fun advertise() {
        val n = node ?: return
        val txt = Txt.encode(n.identity.deviceId, prefs.username, prefs.nickname, deviceModel(), prefs.icon, lan.port)
        lan.advertise(txt)
        if (wifiDirect.supported) wifiDirect.advertise(txt)
    }

    /** Scanning also makes this phone visible, so two phones on the discovery screen see each other. */
    fun startScanning() = scope.launch(Dispatchers.IO) {
        ensureStarted()
        scanning.value = true
        setVisibleInternal(true)
        lan.startDiscovery()
        if (wifiDirect.supported) wifiDirect.startDiscovery()
        if (bluetooth.supported && bluetooth.enabled) bluetooth.startDiscovery()
        pruneLoop()
    }

    fun stopScanning() {
        if (!scanning.value) return
        scanning.value = false
        if (!isStarted) return
        lan.stopDiscovery()
        wifiDirect.stopDiscovery()
        bluetooth.stopDiscovery()
        if (!receiveWaiting.value) setVisibleInternal(false)
        nearby.value = emptyList()
    }

    /** "Receive" mode on the home screen: visible and waiting, without active scanning. */
    val receiveWaiting = MutableStateFlow(false)

    fun startReceiveMode() = scope.launch(Dispatchers.IO) {
        ensureStarted()
        receiveWaiting.value = true
        setVisibleInternal(true)
        ShareService.update(app)
    }

    fun stopReceiveMode() {
        receiveWaiting.value = false
        if (!scanning.value) setVisibleInternal(false)
        ShareService.update(app)
    }

    private fun setVisibleInternal(v: Boolean) {
        if (!isStarted) return
        if (v) {
            advertise()
            if (bluetooth.supported && bluetooth.enabled) {
                bluetooth.startServer()
                bluetooth.advertiseName(prefs.username)
            }
            usb.start()
        } else {
            lan.unregister()
            bluetooth.restoreName()
            bluetooth.stopServer()
        }
        visible.value = v
    }

    private var pruneJob: Job? = null
    private fun pruneLoop() {
        if (pruneJob?.isActive == true) return
        pruneJob = scope.launch {
            while (isActive && scanning.value) {
                delay(10_000)
                val cutoff = System.currentTimeMillis() - 90_000
                nearby.value = nearby.value.filter { it.lastSeen > cutoff }
            }
        }
    }

    private fun onFound(d: NearbyDevice) {
        val self = node?.identity?.deviceId
        if (d.deviceId == self) return
        val cur = nearby.value
        val existing = cur.firstOrNull { it.deviceId == d.deviceId }
        nearby.value = if (existing == null) cur + d else cur.map { if (it.deviceId == d.deviceId) it.merge(d) else it }
    }

    private fun onLost(kind: LinkKind, id: String) {
        nearby.value = nearby.value.mapNotNull { d ->
            if (d.deviceId != id) d
            else {
                val left = when (kind) {
                    LinkKind.LAN -> d.copy(lanHost = null)
                    LinkKind.WIFI_DIRECT -> d.copy(p2pAddress = null)
                    LinkKind.BLUETOOTH -> d.copy(btAddress = null)
                    else -> d
                }
                if (left.via.isEmpty()) null else left
            }
        }
    }

    // ---- Connections -----------------------------------------------------------------------
    private fun onIncomingLink(link: Link) {
        val n = node ?: run { link.close(); return }
        n.io.execute { n.accept(link) }
    }

    /**
     * Connects to a discovered device using the fastest mutually available method:
     * same Wi-Fi network (no setup) → Wi-Fi Direct (no router, high throughput) → Bluetooth.
     */
    fun connect(device: NearbyDevice, action: PeerAction) {
        if (connecting.value != null) return
        lastDevice = device
        connecting.value = ConnectingUi(device, null, action)
        scope.launch(Dispatchers.IO) {
            val n = ensureStarted()
            stopDiscoveryForConnect()
            n.connectionTo(device.deviceId)?.let { onConnectedReady(it, action); return@launch }
            val tuning = socketTuning()
            var lastError = ConnectError.NO_ROUTE
            val attempts = buildList {
                if (device.lanHost != null) add(LinkKind.LAN)
                if (device.p2pAddress != null && wifiDirect.supported) add(LinkKind.WIFI_DIRECT)
                if (device.btAddress != null && bluetooth.supported) add(LinkKind.BLUETOOTH)
            }
            for (kind in attempts) {
                connecting.value = ConnectingUi(device, kind, action)
                try {
                    val link = when (kind) {
                        LinkKind.LAN -> lan.dial(device, tuning)
                        LinkKind.WIFI_DIRECT -> wifiDirect.connect(device, tuning)
                        else -> bluetooth.connect(device)
                    }
                    // Bluetooth discovery ids are placeholders, so identity pinning applies to Wi-Fi paths only.
                    val expected = if (kind == LinkKind.BLUETOOTH) null else device.deviceId
                    if (connectLink(link, expected, action)) return@launch
                    lastError = ConnectError.AUTH_FAILED
                } catch (e: WifiDirectTransport.GroupOwnerException) {
                    // The peer will dial us over the new group; wait for the session to appear.
                    val conn = waitForConnection(device.deviceId, 20_000)
                    if (conn != null) { onConnectedReady(conn, action); return@launch }
                    lastError = ConnectError.TIMEOUT
                } catch (e: SecurityException) {
                    lastError = ConnectError.NEEDS_PERMISSION
                } catch (e: Exception) {
                    lastError = when (kind) {
                        LinkKind.WIFI_DIRECT -> ConnectError.WIFI_DIRECT_FAILED
                        LinkKind.BLUETOOTH -> ConnectError.BLUETOOTH_FAILED
                        else -> ConnectError.NO_ROUTE
                    }
                }
            }
            connecting.value = null
            _events.tryEmit(UiEvent.ConnectFailed(lastError))
        }
    }

    private fun stopDiscoveryForConnect() {
        // Wi-Fi Direct / Bluetooth scans compete with the data link for airtime; stop them.
        if (isStarted) { wifiDirect.stopDiscovery(); bluetooth.stopDiscovery() }
    }

    private fun waitForConnection(id: String, timeoutMs: Long): Connection? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            node?.connectionTo(id)?.let { return it }
            Thread.sleep(200)
        }
        return null
    }

    private fun connectLink(link: Link, expectedId: String?, action: PeerAction): Boolean {
        val n = node ?: return false
        return try {
            val conn = n.connect(link, expectedId)
            onConnectedReady(conn, action)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun onConnectedReady(conn: Connection, action: PeerAction) {
        connecting.value = null
        if (action == PeerAction.RECEIVE) conn.requestReceive()
        _events.tryEmit(UiEvent.Connected(action))
    }

    fun currentConnection(): Connection? = node?.connections?.firstOrNull()

    fun disconnect() {
        val c = currentConnection() ?: return
        lastDevice = null
        c.disconnect()
        if (c.kind == LinkKind.WIFI_DIRECT) wifiDirect.removeGroup()
    }

    // ---- Transfers -------------------------------------------------------------------------
    fun send(items: List<PickItem>, groups: List<GroupInfo> = emptyList()): Boolean {
        val conn = currentConnection() ?: run { _events.tryEmit(UiEvent.Message(Msg.NOT_CONNECTED)); return false }
        if (items.isEmpty()) return false
        val src = PickSource(app, items, groups)
        scope.launch(Dispatchers.IO) {
            val t = conn.send(src, prefs.turbo)
            sources[t.id] = src
            track(t)
            ShareService.update(app)
        }
        _events.tryEmit(UiEvent.OpenTransfer)
        return true
    }

    private fun track(t: Transfer) {
        val first = live.put(t.id, t) == null
        if (first) synchronized(order) { order.remove(t.id); order.add(0, t.id) }
        publish()
        ensureTicker()
    }

    fun pause(id: String, paused: Boolean) {
        val t = live[id] ?: return
        currentConnection()?.pause(t, paused)
    }

    fun cancel(id: String) {
        val t = live[id] ?: return
        recovering.remove(id)
        if (t.state == TransferState.INTERRUPTED) {
            t.let { finalize(it, cancelled = true) }
            return
        }
        currentConnection()?.cancel(t)
    }

    /** Retries/resumes an interrupted or failed outgoing transfer on the current connection. */
    fun retry(id: String) {
        val t = live[id] ?: return
        val src = sources[id] ?: return
        val conn = currentConnection() ?: run { _events.tryEmit(UiEvent.Message(Msg.NOT_CONNECTED)); return }
        scope.launch(Dispatchers.IO) {
            val nt = conn.resume(t, src, prefs.turbo)
            live.remove(id)
            synchronized(order) { val i = order.indexOf(id); if (i >= 0) order[i] = nt.id else order.add(0, nt.id) }
            sources.remove(id)
            sources[nt.id] = src
            live[nt.id] = nt
            publish()
            ensureTicker()
        }
    }

    fun clearFinished() {
        val done = live.values.filter { it.state.terminal }.map { it.id }
        done.forEach { live.remove(it); sources.remove(it) }
        synchronized(order) { order.removeAll(done.toSet()) }
        publish()
    }

    fun transfer(id: String): Transfer? = live[id]

    fun accept(req: IncomingRequest) {
        req.accept(prefs.conflictPolicy)
        incoming.value = null
        Notifications.cancelIncoming(app)
        _events.tryEmit(UiEvent.OpenTransfer)
    }

    fun reject(req: IncomingRequest) {
        req.reject()
        incoming.value = null
        Notifications.cancelIncoming(app)
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                publish()
                ShareService.update(app)
                val active = live.values.any { !it.state.terminal }
                if (!active && recovering.isEmpty()) break
                delay(500)
            }
            publish()
        }
    }

    /** Builds UI snapshots. Speed comes from real byte counters over real elapsed time. */
    private fun publish() {
        val now = System.nanoTime()
        val list = synchronized(order) { order.mapNotNull { live[it] } }
        transfers.value = list.map { t ->
            val done = t.doneBytes
            val running = t.state == TransferState.RUNNING && !t.paused
            val speed = if (running) t.speed.sample(done, now) else 0.0
            val avg = t.speed.average(done, now)
            val tuning = t.tuning
            TransferUi(
                id = t.id, direction = t.direction, peerName = t.peer.profile.username, peerModel = t.peer.profile.model,
                peerId = t.peer.deviceId, method = t.linkKind, state = t.state, reason = t.reason, paused = t.paused,
                totalBytes = t.totalBytes, doneBytes = done, progress = t.progress, speed = speed, avgSpeed = avg,
                etaSec = if (running) t.speed.etaSeconds(t.totalBytes - done, done, now) else -1,
                fileCount = t.fileCount, doneFiles = t.count(FileStatus.DONE), failedFiles = t.count(FileStatus.FAILED),
                skippedFiles = t.count(FileStatus.SKIPPED),
                turbo = tuning?.turbo ?: com.starlane.core.engine.TurboStatus.OFF,
                turboNote = tuning?.note ?: com.starlane.core.engine.TurboNote.NONE,
                lanes = t.activeLanes, resumable = t.direction == Direction.SEND && sources.containsKey(t.id) &&
                    (t.state == TransferState.INTERRUPTED || t.state == TransferState.FAILED),
                appCount = t.manifest.groups.count { it.type == GroupInfo.TYPE_APP }, recovering = recovering.contains(t.id),
            )
        }
        updateConnUi()
    }

    private fun updateConnUi() {
        val c = currentConnection()
        if (c == null) { connection.value = null; return }
        var rssi: Int? = null
        var mbps: Int? = null
        if (c.kind == LinkKind.LAN) {
            // Real values reported by the Wi-Fi driver for the current access-point link.
            @Suppress("DEPRECATION")
            val info = (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            if (info != null && info.rssi > -127 && info.rssi < 0) rssi = info.rssi
            if (info != null && info.linkSpeed > 0) mbps = info.linkSpeed
        }
        val p = c.peer.profile
        connection.value = ConnUi(
            c.peer.deviceId, p.username, p.nickname, p.model, p.icon, c.kind, c.cipherSuite?.name ?: "", c.laneCount,
            c.rttMs, rssi, mbps, c.connectedAt,
        )
    }

    // ---- Recovery --------------------------------------------------------------------------
    /** After an unexpected disconnect, reconnect to the same device and resume interrupted sends. */
    private fun scheduleRecovery(t: Transfer) {
        val dev = lastDevice ?: return
        if (t.direction != Direction.SEND || !sources.containsKey(t.id)) return
        if (!recovering.add(t.id)) return
        _events.tryEmit(UiEvent.Message(Msg.RECOVERING))
        scope.launch(Dispatchers.IO) {
            val n = ensureStarted()
            var conn: Connection? = null
            for (attempt in 0 until 5) {
                if (!recovering.contains(t.id)) return@launch
                delay(1500L * (attempt + 1))
                conn = n.connectionTo(t.peer.deviceId)
                if (conn != null) break
                val link = runCatching {
                    when {
                        dev.lanHost != null -> lan.dial(dev, socketTuning())
                        dev.p2pAddress != null -> wifiDirect.connect(dev, socketTuning())
                        else -> null
                    }
                }.getOrNull() ?: continue
                conn = runCatching { n.connect(link, t.peer.deviceId) }.getOrNull()
                if (conn != null) break
            }
            if (!recovering.remove(t.id)) return@launch
            if (conn == null) {
                _events.tryEmit(UiEvent.Message(Msg.RECOVERY_FAILED))
                publish()
                return@launch
            }
            _events.tryEmit(UiEvent.Message(Msg.RECOVERED))
            retry(t.id)
        }
        ensureTicker()
    }

    private fun finalize(t: Transfer, cancelled: Boolean = false) {
        val avg = t.speed.average(t.doneBytes)
        scope.launch(Dispatchers.IO) {
            val e = HistoryEntry.from(t, avg).let { if (cancelled) it.copy(state = TransferState.CANCELLED.name) else it }
            history.add(e)
            devices.touch(t.peer.deviceId, t.peer.profile.username, t.peer.profile.nickname, t.peer.profile.model, t.peer.profile.icon, t.linkKind.name, true)
        }
        if (cancelled) { live.remove(t.id); sources.remove(t.id); synchronized(order) { order.remove(t.id) } }
        publish()
    }

    private object Listener : NodeListener {
        override fun onConnected(conn: Connection) {
            scope.launch(Dispatchers.IO) {
                val p = conn.peer.profile
                devices.touch(conn.peer.deviceId, p.username, p.nickname, p.model, p.icon, conn.kind.name, false)
            }
            updateConnUi()
            ShareService.update(app)
        }

        override fun onDisconnected(conn: Connection, reason: String) {
            updateConnUi()
            if (reason != "bye") _events.tryEmit(UiEvent.Message(Msg.DISCONNECTED))
            if (conn.kind == LinkKind.USB) usb.linkClosed()
            ShareService.update(app)
        }

        override fun onIncomingRequest(req: IncomingRequest) {
            track(req.transfer)
            incoming.value = req
            Notifications.showIncoming(app, req)
            ShareService.update(app)
        }

        override fun onReceiveRequested(conn: Connection) {
            _events.tryEmit(UiEvent.Message(Msg.PEER_WANTS_TO_RECEIVE))
        }

        override fun onTransferChanged(t: Transfer) {
            if (!live.containsKey(t.id) && t.direction == Direction.SEND) track(t)
            if (t.state.terminal || t.state == TransferState.INTERRUPTED) {
                if (incoming.value?.transfer?.id == t.id) { incoming.value = null; Notifications.cancelIncoming(app) }
                if (t.state == TransferState.INTERRUPTED && t.reason == Reasons.CONNECTION_LOST) scheduleRecovery(t)
                if (t.state.terminal) {
                    finalize(t)
                    Notifications.showFinished(app, t)
                }
            }
            ensureTicker()
        }
    }

    // ---- Misc -------------------------------------------------------------------------------
    fun hasActiveWork(): Boolean = live.values.any { !it.state.terminal } || recovering.isNotEmpty()

    fun capabilitySummary(): Capability {
        if (!isStarted) ensureStarted()
        val pm = app.packageManager
        return Capability(
            wifiDirect = wifiDirect.supported,
            wifiAware = Build.VERSION.SDK_INT >= 26 && pm.hasSystemFeature("android.hardware.wifi.aware"),
            bluetooth = bluetooth.supported,
            bluetoothOn = bluetooth.enabled,
            usbHost = usb.hostSupported,
            usbAccessory = usb.accessorySupported,
            lowEnd = deviceProfile.lowEnd,
            cores = deviceProfile.cores,
            ramMb = deviceProfile.totalRamMb,
            cipherBench = bench.mapKeys { it.key.name },
            turboAvailable = !deviceProfile.lowEnd,
        )
    }

    data class Capability(
        val wifiDirect: Boolean, val wifiAware: Boolean, val bluetooth: Boolean, val bluetoothOn: Boolean,
        val usbHost: Boolean, val usbAccessory: Boolean, val lowEnd: Boolean, val cores: Int, val ramMb: Long,
        val cipherBench: Map<String, Int>, val turboAvailable: Boolean,
    )

    fun handleUsbIntent(intent: Intent) {
        scope.launch(Dispatchers.IO) {
            ensureStarted()
            usb.start()
            usb.onAccessoryIntent(intent)
        }
    }

    fun startUsb() = scope.launch(Dispatchers.IO) { ensureStarted(); usb.start() }

    fun emit(e: UiEvent) { _events.tryEmit(e) }

    /** Releases radios when the app is fully idle (no session, no transfer, not visible). */
    fun releaseIfIdle() {
        if (!isStarted || hasActiveWork() || currentConnection() != null || visible.value || scanning.value) return
        wifiDirect.release()
        bluetooth.release()
    }
}
