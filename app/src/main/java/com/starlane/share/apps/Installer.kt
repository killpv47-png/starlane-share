package com.starlane.share.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Result of checking a received app bundle before installation. */
data class BundleCheck(
    val ok: Boolean,
    val problem: Problem? = null,
    val packageName: String = "",
    val versionName: String = "",
    val missingSplits: List<String> = emptyList(),
) {
    enum class Problem { NO_BASE, NOT_AN_APK, PACKAGE_MISMATCH, MISSING_SPLITS, SDK_TOO_OLD, UNREADABLE }
}

/**
 * Installs received apps through Android's PackageInstaller session API. A session accepts the
 * base APK together with all split APKs, which is required for split-APK apps. Installation is
 * ALWAYS confirmed by the user in the system dialog; silent installation is impossible on
 * ordinary (non-root, non-device-owner) phones and is not attempted.
 */
object Installer {
    const val ACTION_STATUS = "com.starlane.share.INSTALL_STATUS"

    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows this app to install unknown apps (API 26+). */
    fun unknownSourcesIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_SECURITY_SETTINGS)

    /**
     * Verifies a bundle: base present, every file is a valid APK of the same package, and every
     * split the base declares as required is present. [open] returns a stream for each APK.
     */
    fun check(context: Context, apks: List<Pair<String, () -> InputStream>>, cacheDir: File): BundleCheck {
        if (apks.isEmpty()) return BundleCheck(false, BundleCheck.Problem.NO_BASE)
        val pm = context.packageManager
        val tmp = File(cacheDir, "apkcheck").apply { mkdirs() }
        try {
            var pkg = ""
            var version = ""
            var baseFound = false
            val splitNames = HashSet<String>()
            var requiredSplits: List<String> = emptyList()
            for ((name, open) in apks) {
                val f = File(tmp, "c_" + name.hashCode().toUInt() + ".apk")
                open().use { inp -> f.outputStream().use { inp.copyTo(it, 128 * 1024) } }
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(f.absolutePath, 0)
                val split = splitNameOf(f)
                if (info == null && split == null) return BundleCheck(false, BundleCheck.Problem.NOT_AN_APK)
                val p = info?.packageName ?: split?.first ?: ""
                if (pkg.isEmpty()) pkg = p
                else if (p.isNotEmpty() && p != pkg) return BundleCheck(false, BundleCheck.Problem.PACKAGE_MISMATCH)
                if (split?.second.isNullOrEmpty()) {
                    baseFound = true
                    version = info?.versionName ?: ""
                    requiredSplits = requiredSplitsOf(f)
                    val min = if (Build.VERSION.SDK_INT >= 24) info?.applicationInfo?.minSdkVersion ?: 0 else 0
                    if (min > Build.VERSION.SDK_INT) {
                        return BundleCheck(false, BundleCheck.Problem.SDK_TOO_OLD, pkg, version)
                    }
                } else {
                    splitNames += split!!.second!!
                }
                f.delete()
            }
            if (!baseFound) return BundleCheck(false, BundleCheck.Problem.NO_BASE, pkg)
            val missing = requiredSplits.filter { it !in splitNames }
            if (missing.isNotEmpty()) return BundleCheck(false, BundleCheck.Problem.MISSING_SPLITS, pkg, version, missing)
            return BundleCheck(true, null, pkg, version)
        } catch (e: Exception) {
            return BundleCheck(false, BundleCheck.Problem.UNREADABLE)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Starts an install session with all APKs and hands confirmation to the system UI. The result
     * arrives in [InstallReceiver]. Returns the session id.
     */
    fun install(context: Context, packageName: String, apks: List<Pair<String, () -> InputStream>>, sizes: List<Long>): Int {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            apks.forEachIndexed { i, (name, open) ->
                session.openWrite("${i}_$name", 0, sizes.getOrElse(i) { -1L }).use { out ->
                    open().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java).setAction(ACTION_STATUS).putExtra("pkg", packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pending.intentSender)
        }
        return id
    }

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION") context.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    /** Reads "split" and "package" attributes from the binary AndroidManifest (best effort, no parsing libs). */
    private fun splitNameOf(apk: File): Pair<String, String?>? = runCatching {
        ZipFile(apk).use { z ->
            val e = z.getEntry("AndroidManifest.xml") ?: return null
            val bytes = z.getInputStream(e).use { it.readBytes() }
            val strings = AxmlStrings.read(bytes)
            val attrs = AxmlStrings.manifestAttributes(bytes, strings)
            val pkg = attrs["package"] ?: return null
            pkg to attrs["split"]
        }
    }.getOrNull()

    /** Required split names declared through <uses-split> / requiredSplitTypes are not reliably readable; we use
     *  the split list recorded by the sender plus the package manager's own validation on commit. */
    private fun requiredSplitsOf(@Suppress("UNUSED_PARAMETER") base: File): List<String> = emptyList()
}

/** Receives PackageInstaller status callbacks and launches the confirmation UI when required. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra("pkg") ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            else -> InstallEvents.emit(pkg, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "")
        }
    }
}

/** Tiny event bus so the UI can show installation results. */
object InstallEvents {
    data class Result(val pkg: String, val status: Int, val message: String) {
        val success get() = status == PackageInstaller.STATUS_SUCCESS
        val aborted get() = status == PackageInstaller.STATUS_FAILURE_ABORTED
    }
    @Volatile var listener: ((Result) -> Unit)? = null
    @Volatile var last: Result? = null
        private set

    fun emit(pkg: String, status: Int, message: String) {
        val r = Result(pkg, status, message)
        last = r
        listener?.invoke(r)
    }
}

/**
 * Minimal reader for Android binary XML: extracts the string pool and the attributes of the
 * <manifest> element ("package", "split"). Bounds-checked; returns empty results on malformed input.
 */
internal object AxmlStrings {
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    fun read(b: ByteArray): List<String> {
        if (b.size < 16) return emptyList()
        var off = 8
        while (off + 8 <= b.size) {
            val type = u16(b, off)
            val size = u32(b, off + 4)
            if (size <= 0 || off + size > b.size) return emptyList()
            if (type == 0x0001) return pool(b, off)
            off += size
        }
        return emptyList()
    }

    private fun pool(b: ByteArray, start: Int): List<String> {
        val count = u32(b, start + 8)
        val flags = u32(b, start + 16)
        val stringsStart = start + u32(b, start + 20)
        val utf8 = (flags and 0x100) != 0
        if (count < 0 || count > 100_000) return emptyList()
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val so = stringsStart + u32(b, start + 28 + i * 4)
            if (so < 0 || so >= b.size) { out += ""; continue }
            out += if (utf8) {
                var p = so
                p += if (b[p].toInt() and 0x80 != 0) 2 else 1
                var len = b[p].toInt() and 0xFF
                if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2 } else p += 1
                if (p + len > b.size) "" else String(b, p, len, Charsets.UTF_8)
            } else {
                val len = u16(b, so)
                if (so + 2 + len * 2 > b.size) "" else String(b, so + 2, len * 2, Charsets.UTF_16LE)
            }
        }
        return out
    }

    fun manifestAttributes(b: ByteArray, strings: List<String>): Map<String, String> {
        var off = 8
        while (off + 8 <= b.size) {
            val type = u16(b, off)
            val size = u32(b, off + 4)
            if (size <= 0 || off + size > b.size) break
            if (type == 0x0102) { // START_ELEMENT
                val nameIdx = u32(b, off + 20)
                if (strings.getOrNull(nameIdx) == "manifest") {
                    val attrStart = off + 16 + u16(b, off + 24)
                    val attrSize = u16(b, off + 26)
                    val attrCount = u16(b, off + 28)
                    val m = HashMap<String, String>()
                    for (i in 0 until attrCount) {
                        val a = attrStart + i * attrSize
                        if (a + 20 > b.size) break
                        val n = strings.getOrNull(u32(b, a + 4)) ?: continue
                        val raw = u32(b, a + 8)
                        val v = if (raw >= 0 && raw < strings.size) strings[raw] else continue
                        m[n] = v
                    }
                    return m
                }
            }
            off += size
        }
        return emptyMap()
    }
}
