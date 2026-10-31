package com.starlane.share.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.starlane.core.link.Link
import com.starlane.core.link.LinkKind
import com.starlane.core.link.StreamLink
import java.io.BufferedOutputStream
import java.util.UUID

/**
 * Bluetooth Classic (RFCOMM) fallback transport. Much slower than Wi-Fi (typically well under
 * 1 MB/s) but works on almost every phone. The same encrypted Starlane protocol runs on top, so
 * peers are still authenticated by their identity keys.
 *
 * Discovery: classic inquiry finds devices whose Bluetooth name starts with the Starlane prefix
 * (the app temporarily sets "SL·<username>" while the user is visible). Only a short prefix is
 * shown; full profile details arrive in the authenticated handshake.
 */
@SuppressLint("MissingPermission") // Runtime permissions are requested by the UI before any call.
class BluetoothTransport(
    private val context: Context,
    private val onLink: (Link) -> Unit,
    private val sink: DiscoverySink,
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var server: BluetoothServerSocket? = null
    private var receiver: BroadcastReceiver? = null
    private var originalName: String? = null
    private val found = HashMap<String, String>() // address -> pseudo id

    val supported: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    @Synchronized
    fun startServer() {
        val a = adapter ?: return
        if (server != null || !a.isEnabled) return
        val s = runCatching { a.listenUsingRfcommWithServiceRecord("StarlaneShare", SERVICE_UUID) }.getOrNull() ?: return
        server = s
        Thread({
            while (true) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                onLink(wrap(sock))
            }
        }, "starlane-bt-accept").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stopServer() {
        runCatching { server?.close() }
        server = null
    }

    /** Sets a recognisable adapter name while visible; restored by [restoreName]. */
    fun advertiseName(username: String) {
        val a = adapter ?: return
        if (!a.isEnabled) return
        if (originalName == null) originalName = runCatching { a.name }.getOrNull()
        runCatching { a.name = PREFIX + username.take(20) }
    }

    fun restoreName() {
        val a = adapter ?: return
        val n = originalName ?: return
        runCatching { a.name = n }
        originalName = null
    }

    fun startDiscovery() {
        val a = adapter ?: return
        if (!a.isEnabled) return
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action != BluetoothDevice.ACTION_FOUND) return
                    @Suppress("DEPRECATION")
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val name = runCatching { d.name }.getOrNull() ?: return
                    if (!name.startsWith(PREFIX)) return
                    // Real id is only known after the authenticated handshake; use a stable placeholder key.
                    val id = "bt" + d.address.replace(":", "").lowercase().take(14)
                    found[d.address] = id
                    sink.found(NearbyDevice(id, name.removePrefix(PREFIX), "", "Bluetooth", 0, btAddress = d.address))
                }
            }
            val f = IntentFilter(BluetoothDevice.ACTION_FOUND)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, f)
            receiver = r
        }
        runCatching { a.cancelDiscovery(); a.startDiscovery() }
    }

    fun stopDiscovery() {
        runCatching { adapter?.cancelDiscovery() }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        found.values.toList().forEach { sink.lost(LinkKind.BLUETOOTH, it) }
        found.clear()
    }

    /** Blocking RFCOMM connect (pairing dialog may appear on both phones). */
    fun connect(device: NearbyDevice): Link {
        val a = adapter ?: throw IllegalStateException("Bluetooth unsupported")
        val addr = device.btAddress ?: throw IllegalStateException("no Bluetooth address")
        runCatching { a.cancelDiscovery() } // discovery drastically slows down connections
        val remote = a.getRemoteDevice(addr)
        val sock = remote.createRfcommSocketToServiceRecord(SERVICE_UUID)
        try {
            sock.connect()
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e
        }
        return wrap(sock)
    }

    private fun wrap(sock: BluetoothSocket): Link = StreamLink(
        LinkKind.BLUETOOTH, sock.inputStream, BufferedOutputStream(sock.outputStream, 16 * 1024),
        "Bluetooth ${runCatching { sock.remoteDevice.address }.getOrDefault("")}",
    ) { runCatching { sock.close() } }

    fun release() {
        stopDiscovery()
        stopServer()
        restoreName()
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6f0c6c8e-3b2f-4a8e-9a52-5d1b7c0e4a21")
        const val PREFIX = "SL\u00B7"
    }
}
