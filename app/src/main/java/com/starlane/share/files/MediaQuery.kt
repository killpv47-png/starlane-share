package com.starlane.share.files

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.documentfile.provider.DocumentFile
import com.starlane.core.engine.MimeTypes

enum class MediaKind { PHOTOS, VIDEOS, AUDIO, DOCUMENTS }

/** MediaStore queries. Results are capped so huge libraries never blow up memory. */
object MediaQuery {
    private const val LIMIT = 3000

    fun load(context: Context, kind: MediaKind): List<PickItem> {
        val collection: Uri
        var selection: String? = null
        var args: Array<String>? = null
        when (kind) {
            MediaKind.PHOTOS -> collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaKind.VIDEOS -> collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaKind.AUDIO -> collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            MediaKind.DOCUMENTS -> {
                // Only readable through MediaStore on API <= 29 (with storage permission); newer
                // Android versions require the system picker for documents, which the UI offers.
                if (Build.VERSION.SDK_INT >= 30) return emptyList()
                collection = MediaStore.Files.getContentUri("external")
                val mimes = listOf(
                    "application/pdf", "text/plain", "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/zip", "application/epub+zip", "text/csv",
                )
                selection = MediaStore.Files.FileColumns.MIME_TYPE + " IN (" + mimes.joinToString(",") { "?" } + ")"
                args = mimes.toTypedArray()
            }
        }
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val out = ArrayList<PickItem>()
        runCatching {
            context.contentResolver.query(collection, proj, selection, args, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext() && out.size < LIMIT) {
                    val size = c.getLong(iSize)
                    if (size <= 0) continue
                    val name = c.getString(iName) ?: continue
                    val uri = ContentUris.withAppendedId(collection, c.getLong(iId))
                    out += PickItem(name, size, c.getString(iMime) ?: MimeTypes.guess(name), uri = uri, lastModified = c.getLong(iDate) * 1000)
                }
            }
        }
        return out
    }

    /** Recursively lists a SAF folder, keeping the folder structure in relative paths. */
    fun listTree(context: Context, tree: Uri, maxFiles: Int = 20_000): List<PickItem> {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return emptyList()
        val rootName = root.name ?: "Folder"
        val out = ArrayList<PickItem>()
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.add(root to rootName)
        while (stack.isNotEmpty() && out.size < maxFiles) {
            val (dir, path) = stack.removeLast()
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) stack.add(f to "$path/$name")
                else if (f.isFile && f.length() >= 0) {
                    out += PickItem(name, f.length(), f.type ?: MimeTypes.guess(name), uri = f.uri, relPath = "$path/$name", lastModified = f.lastModified())
                }
            }
        }
        return out
    }

    // ---- Thumbnails (small LRU cache, sized for low-end phones) ----------------------------
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceIn(2 * 1024 * 1024, 24 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cachedThumb(uri: Uri): Bitmap? = cache.get(uri.toString())

    @Suppress("DEPRECATION")
    fun thumb(context: Context, uri: Uri, isVideo: Boolean, px: Int): Bitmap? {
        cache.get(uri.toString())?.let { return it }
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(px, px), null)
            else {
                val id = ContentUris.parseId(uri)
                if (isVideo) MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MICRO_KIND, null)
                else MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MICRO_KIND, null)
            }
        }.getOrNull() ?: return null
        cache.put(uri.toString(), bmp)
        return bmp
    }
}
