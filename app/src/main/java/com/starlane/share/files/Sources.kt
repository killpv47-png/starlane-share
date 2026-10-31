package com.starlane.share.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.starlane.core.crypto.sha256
import com.starlane.core.engine.ChannelSourceReader
import com.starlane.core.engine.MimeTypes
import com.starlane.core.engine.SourceReader
import com.starlane.core.engine.StreamSourceReader
import com.starlane.core.engine.TransferSource
import com.starlane.core.proto.FileEntry
import com.starlane.core.proto.FileKind
import com.starlane.core.proto.GroupInfo
import com.starlane.core.util.Hex
import java.io.File
import java.io.FileInputStream

/**
 * One item the user selected. Either a content Uri (MediaStore, SAF, Sharesheet) or a plain file
 * path the app may read directly (installed APKs, app-specific folders).
 */
data class PickItem(
    val name: String,
    val size: Long,
    val mime: String,
    val uri: Uri? = null,
    val file: File? = null,
    /** Path inside the transfer, e.g. "Album/IMG_1.jpg" for folder selections. */
    val relPath: String = name,
    val kind: FileKind = FileKind.FILE,
    val group: String = "",
    val lastModified: Long = 0,
    val meta: Map<String, String> = emptyMap(),
) {
    /** Stable key for resume that never reveals the private path to the peer. */
    val key: String get() = Hex.encode(sha256(((uri?.toString() ?: file?.absolutePath ?: name) + "|" + size).toByteArray()), 12)
}

/**
 * Streams selected items directly from storage. File descriptors are opened per file only while
 * that file is being sent, so huge selections never hold many descriptors or any file in memory.
 */
class PickSource(
    private val context: Context,
    private val items: List<PickItem>,
    override val groups: List<GroupInfo> = emptyList(),
) : TransferSource {
    override val entries: List<FileEntry> = items.mapIndexed { i, it ->
        FileEntry(
            index = i, relPath = it.relPath, size = it.size, mime = it.mime, kind = it.kind,
            group = it.group, key = it.key, lastModified = it.lastModified, meta = it.meta,
        )
    }

    val totalBytes: Long get() = items.sumOf { it.size }

    override fun open(index: Int): SourceReader {
        val it = items[index]
        it.file?.let { f -> return ChannelSourceReader.of(f) }
        val uri = it.uri ?: throw IllegalStateException("no data source")
        // Seekable descriptors allow parallel lanes; fall back to a sequential stream otherwise.
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd != null) {
            val fis = FileInputStream(pfd.fileDescriptor)
            val ch = fis.channel
            val seekable = runCatching { ch.position(0); ch.size() >= 0 }.getOrDefault(false)
            if (seekable) return ChannelSourceReader(ch) { runCatching { fis.close() }; runCatching { pfd.close() } }
            return StreamSourceReader(fis).also { }
        }
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open")
        return StreamSourceReader(input)
    }
}

object UriInfo {
    /** Resolves display name, size and mime for a content Uri (used for Sharesheet and SAF picks). */
    fun describe(context: Context, uri: Uri, relPrefix: String = ""): PickItem? {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            if (!f.isFile) return null
            return PickItem(f.name, f.length(), MimeTypes.guess(f.name), file = f, relPath = relPrefix + f.name, lastModified = f.lastModified())
        }
        if (size < 0) {
            size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1
        }
        if (size < 0) return null // unknown length: cannot promise integrity/progress, refuse rather than guess
        val n = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = context.contentResolver.getType(uri) ?: MimeTypes.guess(n)
        return PickItem(n, size, mime, uri = uri, relPath = relPrefix + n)
    }
}
