package com.starlane.share.files

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.starlane.core.engine.ConflictPolicy
import com.starlane.core.engine.FileSystemStorage
import com.starlane.core.engine.ResumeStore
import com.starlane.core.engine.SafeNames
import com.starlane.core.proto.FileEntry
import com.starlane.share.Prefs
import java.io.File
import java.io.IOException

/**
 * Receives files on Android.
 *
 * Partial files are always written to app-specific external storage (no permission needed on any
 * API level, same volume as shared storage on almost every device) and only moved/copied to the
 * final destination after their SHA-256 verified. Destinations:
 *  - A folder the user picked with the Storage Access Framework (Settings → Save location).
 *  - Otherwise Download/Starlane: direct file access on API ≤ 29, MediaStore.Downloads on API 30+.
 *  - If neither works, the app-specific "Received" folder is used as a last resort.
 */
class AndroidStorage(
    private val context: Context,
    private val prefs: Prefs,
    resume: ResumeStore,
    private val onCommitted: (FileEntry, String) -> Unit,
) : FileSystemStorage(
    root = defaultRoot(context),
    resume = resume,
    partsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "parts"),
    onCommitted = onCommitted,
) {
    private val privateRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, "Received")

    override fun freeBytes(): Long {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(dir.usableSpace)
    }

    override fun fallbackTargetFor(entry: FileEntry): File = File(privateRoot, SafeNames.sanitizeRelPath(entry.relPath))

    override fun deliver(entry: FileEntry, part: File, policy: ConflictPolicy): String {
        val tree = prefs.saveTreeUri
        if (tree != null) {
            runCatching { return deliverToTree(Uri.parse(tree), entry, part, policy) }
        }
        if (Build.VERSION.SDK_INT >= 30 && !legacyWritable()) {
            runCatching { return deliverToMediaStore(entry, part, policy) }
            return super.deliver(entry, part, policy).also { }
        }
        val location = super.deliver(entry, part, policy)
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(location), null, null) }
        return location
    }

    private fun legacyWritable(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageLegacy()

    /** API 30+: Download/Starlane/<relative path> through MediaStore (no storage permission needed). */
    private fun deliverToMediaStore(entry: FileEntry, part: File, policy: ConflictPolicy): String {
        val rel = SafeNames.sanitizeRelPath(entry.relPath)
        val dir = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/Starlane" + (if (dir.isNotEmpty()) "/$dir" else "") + "/"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var finalName = name
        if (policy != ConflictPolicy.OVERWRITE) {
            var n = 1
            while (mediaStoreExists(relativePath, finalName)) finalName = SafeNames.numbered(name, n++)
        } else {
            findMediaStore(relativePath, name)?.let { resolver.delete(it, null, null) }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri, "w")!!.use { out -> part.inputStream().use { it.copyTo(out, 256 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        part.delete()
        return uri.toString()
    }

    private fun findMediaStore(relativePath: String, name: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath, name), null,
        )?.use { c -> if (c.moveToFirst()) return Uri.withAppendedPath(collection, c.getLong(0).toString()) }
        return null
    }

    private fun mediaStoreExists(relativePath: String, name: String) = findMediaStore(relativePath, name) != null

    /** User-chosen SAF folder. Sub-folders from the sender's relative path are recreated. */
    private fun deliverToTree(treeUri: Uri, entry: FileEntry, part: File, policy: ConflictPolicy): String {
        var dir = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("tree unavailable")
        if (!dir.canWrite()) throw IOException("tree not writable")
        val rel = SafeNames.sanitizeRelPath(entry.relPath)
        val parts = rel.split('/')
        for (p in parts.dropLast(1)) {
            dir = dir.findFile(p)?.takeIf { it.isDirectory } ?: dir.createDirectory(p) ?: throw IOException("mkdir failed")
        }
        val name = parts.last()
        var finalName = name
        val existing = dir.findFile(name)
        if (existing != null) {
            if (policy == ConflictPolicy.OVERWRITE) existing.delete()
            else { var n = 1; while (dir.findFile(finalName) != null) finalName = SafeNames.numbered(name, n++) }
        }
        val doc = dir.createFile(entry.mime, finalName) ?: throw IOException("create failed")
        try {
            context.contentResolver.openOutputStream(doc.uri, "w")!!.use { out -> part.inputStream().use { it.copyTo(out, 256 * 1024) } }
        } catch (e: Exception) {
            doc.delete(); throw e
        }
        part.delete()
        return doc.uri.toString()
    }

    companion object {
        @Suppress("DEPRECATION")
        fun defaultRoot(context: Context): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Starlane")
    }
}
