package com.starlane.share.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import com.starlane.core.link.Link
import com.starlane.core.link.LinkKind
import com.starlane.core.link.StreamLink
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Phone-to-phone USB cable transfer using the Android Open Accessory (AOA) protocol:
 *
 *  - The phone acting as USB HOST (needs USB OTG support and an OTG adapter or USB-C to USB-C
 *    cable where this phone supplies power) sees the other phone as a [UsbDevice]. It sends the
 *    AOA control requests that switch the other phone into accessory mode, then talks to it over
 *    two bulk endpoints.
 *  - The other phone (USB DEVICE role) receives USB_ACCESSORY_ATTACHED and opens a
 *    [ParcelFileDescriptor] for the [UsbAccessory].
 *
 * Many phone/cable combinations cannot do this (no OTG, both phones insisting on the same power
 * role, charge-only cables). [status] reports exactly what was detected instead of faking a
 * connection; the UI then suggests wireless transfer.
 */
class UsbTransport(
    private val context: Context,
    private val onLink: (Link) -> Unit,
) {
    enum class Status {
        NO_USB_SUPPORT,       // device declares neither host nor accessory support
        NOTHING_CONNECTED,    // no USB device or accessory visible
        DEVICE_ATTACHED,      // we are host; another device is attached (permission may be needed)
        SWITCHING,            // AOA requests sent, waiting for the peer to re-enumerate
        ACCESSORY_ATTACHED,   // we are the accessory side
        CONNECTED,
        PEER_NOT_AOA,         // attached device refused AOA (not an Android phone / no support)
        PERMISSION_DENIED,
        FAILED,
    }

    private val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    @Volatile var status: Status = Status.NOTHING_CONNECTED
        private set
    @Volatile var lastError: String = ""
        private set
    var onStatus: ((Status) -> Unit)? = null
    private var receiver: BroadcastReceiver? = null
    @Volatile private var activeLink: Link? = null

    val hostSupported: Boolean get() = context.packageManager.hasSystemFeature("android.hardware.usb.host")
    val accessorySupported: Boolean get() = context.packageManager.hasSystemFeature("android.hardware.usb.accessory")

    private fun set(s: Status, err: String = "") {
        status = s; lastError = err
        onStatus?.invoke(s)
    }

    fun start() {
        if (usb == null || (!hostSupported && !accessorySupported)) { set(Status.NO_USB_SUPPORT); return }
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.action) {
                        ACTION_PERMISSION -> {
                            val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            if (!granted) set(Status.PERMISSION_DENIED) else refresh()
                        }
                        UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> refresh()
                        UsbManager.ACTION_USB_DEVICE_DETACHED, UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                            activeLink?.close(); activeLink = null
                            refresh()
                        }
                    }
                }
            }
            val f = IntentFilter().apply {
                addAction(ACTION_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }
            androidx.core.content.ContextCompat.registerReceiver(context, r, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }
        refresh()
    }

    /** Re-evaluates the USB state and connects when possible. */
    fun refresh() {
        val m = usb ?: return set(Status.NO_USB_SUPPORT)
        if (activeLink != null) return
        val acc = runCatching { m.accessoryList?.firstOrNull { isOurAccessory(it) } }.getOrNull()
        if (acc != null) {
            set(Status.ACCESSORY_ATTACHED)
            if (!m.hasPermission(acc)) { m.requestPermission(acc, permissionIntent()); return }
            openAccessory(m, acc)
            return
        }
        val dev = runCatching { m.deviceList.values.firstOrNull() }.getOrNull()
        if (dev == null) { set(Status.NOTHING_CONNECTED); return }
        set(Status.DEVICE_ATTACHED)
        if (!m.hasPermission(dev)) { m.requestPermission(dev, permissionIntent()); return }
        Thread({ hostConnect(m, dev) }, "starlane-usb").start()
    }

    /** Called by the activity when it was launched by USB_ACCESSORY_ATTACHED. */
    fun onAccessoryIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY) ?: return
        val m = usb ?: return
        if (!isOurAccessory(acc)) return
        if (m.hasPermission(acc)) openAccessory(m, acc) else m.requestPermission(acc, permissionIntent())
    }

    private fun isOurAccessory(a: UsbAccessory) = a.manufacturer == MANUFACTURER && a.model == MODEL

    private fun permissionIntent(): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 7, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
    }

    private fun openAccessory(m: UsbManager, acc: UsbAccessory) {
        val pfd: ParcelFileDescriptor = m.openAccessory(acc) ?: return set(Status.FAILED, "openAccessory returned null")
        val fd = pfd.fileDescriptor
        val link = StreamLink(LinkKind.USB, FileInputStream(fd), FileOutputStream(fd), "USB accessory") { runCatching { pfd.close() } }
        activeLink = link
        set(Status.CONNECTED)
        // The host side dials (initiates the handshake); we accept.
        onLink(link)
    }

    /** Host side: switch the peer to accessory mode if needed, then open bulk endpoints. */
    private fun hostConnect(m: UsbManager, dev: UsbDevice) {
        try {
            if (isAccessoryMode(dev)) {
                openBulk(m, dev)
                return
            }
            val conn = m.openDevice(dev) ?: return set(Status.FAILED, "openDevice failed")
            try {
                val ver = ByteArray(2)
                val r = conn.controlTransfer(0xC0, AOA_GET_PROTOCOL, 0, 0, ver, 2, 1000)
                val protocol = if (r == 2) (ver[0].toInt() and 0xFF) or ((ver[1].toInt() and 0xFF) shl 8) else 0
                if (protocol < 1) { set(Status.PEER_NOT_AOA); return }
                sendString(conn, 0, MANUFACTURER)
                sendString(conn, 1, MODEL)
                sendString(conn, 2, "Starlane Share cable transfer")
                sendString(conn, 3, "1")
                sendString(conn, 4, "https://github.com/killpv47-png/starlane-share")
                sendString(conn, 5, "1")
                conn.controlTransfer(0x40, AOA_START, 0, 0, null, 0, 1000)
                set(Status.SWITCHING)
            } finally {
                conn.close()
            }
            // The peer now re-enumerates with the Google accessory VID/PID; ATTACHED fires again.
        } catch (e: Exception) {
            set(Status.FAILED, e.javaClass.simpleName)
        }
    }

    private fun sendString(c: UsbDeviceConnection, index: Int, s: String) {
        val b = (s + "\u0000").toByteArray()
        c.controlTransfer(0x40, AOA_SEND_STRING, 0, index, b, b.size, 1000)
    }

    private fun isAccessoryMode(d: UsbDevice) = d.vendorId == GOOGLE_VID && d.productId in 0x2D00..0x2D05

    private fun openBulk(m: UsbManager, dev: UsbDevice) {
        val conn = m.openDevice(dev) ?: return set(Status.FAILED, "openDevice failed")
        var iface: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        loop@ for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            var a: UsbEndpoint? = null
            var b: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) a = ep else b = ep
            }
            if (a != null && b != null) { iface = itf; inEp = a; outEp = b; break@loop }
        }
        if (iface == null || inEp == null || outEp == null || !conn.claimInterface(iface, true)) {
            conn.close()
            return set(Status.FAILED, "no bulk endpoints")
        }
        val link = StreamLink(
            LinkKind.USB, BulkInput(conn, inEp), BulkOutput(conn, outEp), "USB host",
        ) { runCatching { conn.releaseInterface(iface) }; runCatching { conn.close() } }
        activeLink = link
        set(Status.CONNECTED)
        onLink(UsbHostLink(link))
    }

    fun linkClosed() { activeLink = null; refresh() }

    fun release() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        activeLink?.close()
        activeLink = null
    }

    /** Marker so the manager knows this side must initiate the handshake. */
    class UsbHostLink(private val inner: Link) : Link by inner

    private class BulkInput(private val c: UsbDeviceConnection, private val ep: UsbEndpoint) : InputStream() {
        private val buf = ByteArray(16 * 1024)
        private var pos = 0
        private var len = 0
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, n: Int): Int {
            if (pos >= len) {
                var r: Int
                do { r = c.bulkTransfer(ep, buf, buf.size, 0) } while (r == 0)
                if (r < 0) throw IOException("USB read failed")
                pos = 0; len = r
            }
            val k = minOf(n, len - pos)
            System.arraycopy(buf, pos, b, off, k)
            pos += k
            return k
        }
    }

    private class BulkOutput(private val c: UsbDeviceConnection, private val ep: UsbEndpoint) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var p = 0
            val tmp = if (off == 0) b else null
            while (p < len) {
                val n = minOf(16 * 1024, len - p)
                val r = if (tmp != null && Build.VERSION.SDK_INT >= 18) {
                    c.bulkTransfer(ep, b, off + p, n, 5000)
                } else {
                    val chunk = b.copyOfRange(off + p, off + p + n)
                    c.bulkTransfer(ep, chunk, n, 5000)
                }
                if (r < 0) throw IOException("USB write failed")
                p += r
            }
        }
    }

    companion object {
        const val MANUFACTURER = "Starlane"
        const val MODEL = "StarlaneShare"
        private const val ACTION_PERMISSION = "com.starlane.share.USB_PERMISSION"
        private const val GOOGLE_VID = 0x18D1
        private const val AOA_GET_PROTOCOL = 51
        private const val AOA_SEND_STRING = 52
        private const val AOA_START = 53
    }
}
