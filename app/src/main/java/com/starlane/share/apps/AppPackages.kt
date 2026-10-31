package com.starlane.share.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import com.starlane.core.engine.MimeTypes
import com.starlane.core.proto.FileKind
import com.starlane.core.proto.GroupInfo
import com.starlane.share.files.PickItem
import java.io.File

/** An installed, launchable application that can be shared. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val apkSize: Long,
    val splitCount: Int,
    val isSystem: Boolean,
    val installedAt: Long,
)

/**
 * Pre-transfer summary for one app with DATA enabled. Every category is explicit so the UI can
 * say exactly what will and will not be transferred - nothing is implied.
 */
data class AppPlan(
    val app: AppEntry,
    val apks: List<File>,
    val apkBytes: Long,
    /** Android/obb/<pkg> files that are readable by this app (API ≤ 29 with storage permission). */
    val obbFiles: List<File>,
    /** Android/media/<pkg> files that are readable. */
    val mediaFiles: List<File>,
    /** Extra files the user added explicitly through the system folder picker (SAF). */
    val extraItems: List<PickItem>,
    /** Folders that exist but Android does not let us read (obb/data on API 30+, etc.). */
    val inaccessible: List<String>,
    /** Private /data/data storage always requires the app's own backup/migration feature. */
    val privateDataNote: Boolean = true,
) {
    val obbBytes get() = obbFiles.sumOf { it.length() }
    val mediaBytes get() = mediaFiles.sumOf { it.length() }
    val extraBytes get() = extraItems.sumOf { it.size }
    val totalBytes get() = apkBytes + obbBytes + mediaBytes + extraBytes
}

object AppPackages {
    /** Lists launchable apps (the manifest <queries> grants visibility to exactly these). Run off the main thread. */
    fun list(context: Context, includeSystem: Boolean = false): List<AppEntry> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val pkgs = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet()
        return pkgs.mapNotNull { p ->
            if (p == context.packageName) return@mapNotNull null
            val pi = packageInfo(pm, p) ?: return@mapNotNull null
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (system && !includeSystem) return@mapNotNull null
            val apks = apkFiles(ai)
            if (apks.isEmpty()) return@mapNotNull null
            AppEntry(
                packageName = p, label = ai.loadLabel(pm).toString(), versionName = pi.versionName ?: "",
                versionCode = versionCode(pi), apkSize = apks.sumOf { it.length() },
                splitCount = apks.size - 1, isSystem = system, installedAt = pi.firstInstallTime,
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun packageInfo(pm: PackageManager, p: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(p, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(p, 0)
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun versionCode(pi: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

    /** Base APK plus every split APK (config/feature splits). All are world-readable on stock Android. */
    fun apkFiles(ai: ApplicationInfo): List<File> {
        val out = ArrayList<File>()
        ai.sourceDir?.let { File(it) }?.takeIf { it.canRead() }?.let { out += it }
        if (Build.VERSION.SDK_INT >= 21) ai.splitSourceDirs?.forEach { s -> File(s).takeIf { it.canRead() }?.let { out += it } }
        return out
    }

    fun icon(context: Context, pkg: String, sizePx: Int): Bitmap? = runCatching {
        val d: Drawable = context.packageManager.getApplicationIcon(pkg)
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(c)
        b
    }.getOrNull()

    /**
     * Builds the transfer plan. Only data readable through supported APIs is included; nothing
     * bypasses the application sandbox.
     */
    @Suppress("DEPRECATION")
    fun plan(context: Context, app: AppEntry, withData: Boolean, extras: List<PickItem>): AppPlan {
        val ai = context.packageManager.getApplicationInfo(app.packageName, 0)
        val apks = apkFiles(ai)
        val obb = ArrayList<File>()
        val media = ArrayList<File>()
        val inaccessible = ArrayList<String>()
        if (withData) {
            val ext = Environment.getExternalStorageDirectory()
            val obbDir = File(ext, "Android/obb/${app.packageName}")
            val mediaDir = File(ext, "Android/media/${app.packageName}")
            collect(obbDir, obb, inaccessible, "Android/obb/${app.packageName}")
            collect(mediaDir, media, inaccessible, "Android/media/${app.packageName}")
            // Android/data/<pkg> is never readable by other apps on API 30+, and only with legacy storage before.
            val dataDir = File(ext, "Android/data/${app.packageName}")
            if (Build.VERSION.SDK_INT >= 30 || dataDir.exists() && dataDir.listFiles() == null) {
                inaccessible += "Android/data/${app.packageName}"
            }
        }
        return AppPlan(app, apks, apks.sumOf { it.length() }, obb, media, if (withData) extras else emptyList(), inaccessible.distinct())
    }

    private fun collect(dir: File, out: MutableList<File>, inaccessible: MutableList<String>, label: String) {
        if (!dir.exists()) {
            // On API 30+ we cannot even tell whether it exists for other apps; report it honestly.
            if (Build.VERSION.SDK_INT >= 30 && label.contains("/obb/")) inaccessible += label
            return
        }
        val children = dir.listFiles()
        if (children == null) { inaccessible += label; return }
        val stack = ArrayDeque(children.toList())
        var count = 0
        while (stack.isNotEmpty() && count < 5000) {
            val f = stack.removeFirst()
            if (f.isDirectory) f.listFiles()?.let { stack.addAll(it) } ?: run { inaccessible += label }
            else if (f.canRead()) { out += f; count++ } else inaccessible += label
        }
    }

    /** Converts a plan to transfer items, grouped so the receiver can install and report per app. */
    fun toItems(plan: AppPlan): Pair<List<PickItem>, GroupInfo> {
        val pkg = plan.app.packageName
        val base = "Apps/${safe(plan.app.label)}_${plan.app.versionName}"
        val items = ArrayList<PickItem>()
        plan.apks.forEachIndexed { i, f ->
            val isBase = i == 0
            items += PickItem(
                name = if (isBase) "base.apk" else f.name, size = f.length(), mime = MimeTypes.guess("x.apk"), file = f,
                relPath = "$base/${if (isBase) "base.apk" else f.name}",
                kind = if (isBase) FileKind.APK_BASE else FileKind.APK_SPLIT, group = pkg, lastModified = f.lastModified(),
            )
        }
        val ext = Environment.getExternalStorageDirectory()
        plan.obbFiles.forEach { f ->
            items += PickItem(f.name, f.length(), "application/octet-stream", file = f,
                relPath = "$base/obb/" + f.relativeTo(File(ext, "Android/obb/$pkg")).path, kind = FileKind.OBB, group = pkg,
                lastModified = f.lastModified(), meta = mapOf("dest" to "Android/obb/$pkg/" + f.relativeTo(File(ext, "Android/obb/$pkg")).path))
        }
        plan.mediaFiles.forEach { f ->
            items += PickItem(f.name, f.length(), MimeTypes.guess(f.name), file = f,
                relPath = "$base/media/" + f.relativeTo(File(ext, "Android/media/$pkg")).path, kind = FileKind.APP_MEDIA, group = pkg,
                lastModified = f.lastModified())
        }
        plan.extraItems.forEach { e -> items += e.copy(relPath = "$base/extra/${e.relPath}", kind = FileKind.APP_EXTRA, group = pkg) }
        val group = GroupInfo(
            pkg, GroupInfo.TYPE_APP,
            mapOf(
                "label" to plan.app.label, "versionName" to plan.app.versionName, "versionCode" to plan.app.versionCode.toString(),
                "splits" to (plan.apks.size - 1).toString(), "obb" to plan.obbFiles.size.toString(),
                "media" to plan.mediaFiles.size.toString(), "extra" to plan.extraItems.size.toString(),
                "inaccessible" to plan.inaccessible.joinToString("|"),
                "minSdk" to "", "private" to "1",
            ),
        )
        return items to group
    }

    private fun safe(s: String) = s.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(40).ifBlank { "app" }
}
