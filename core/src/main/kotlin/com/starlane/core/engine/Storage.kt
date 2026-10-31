package com.starlane.core.engine

import com.starlane.core.crypto.sha256
import com.starlane.core.proto.FileEntry
import com.starlane.core.proto.GroupInfo
import com.starlane.core.util.Hex
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

// ----------------------------------------------------------------------------------------------
// Sending side
// ----------------------------------------------------------------------------------------------

/** Reads file content. Seekable readers allow segments of one file to be sent on parallel lanes. */
interface SourceReader : Closeable {
    val seekable: Boolean

    /** Reads up to [len] bytes at [pos]. For non-seekable readers [pos] must be sequential. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
}

interface TransferSource {
    val entries: List<FileEntry>
    val groups: List<GroupInfo> get() = emptyList()
    fun open(index: Int): SourceReader
}

/** Positional reader over a [FileChannel] (files and seekable content-provider descriptors). */
class ChannelSourceReader(private val channel: FileChannel, private val owner: Closeable? = null) : SourceReader {
    override val seekable = true

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        val bb = ByteBuffer.wrap(buf, off, len)
        var p = pos
        while (bb.hasRemaining()) {
            val n = channel.read(bb, p)
            if (n < 0) break
            p += n
        }
        val got = (p - pos).toInt()
        if (got == 0 && len > 0) throw IOException("unexpected end of file")
        return got
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { owner?.close() }
    }

    companion object {
        fun of(file: File): ChannelSourceReader {
            val raf = RandomAccessFile(file, "r")
            return ChannelSourceReader(raf.channel, raf)
        }
    }
}

/** Sequential reader for streams that cannot seek (e.g. some content providers). */
class StreamSourceReader(private val input: InputStream) : SourceReader {
    override val seekable = false
    private var position = 0L

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos != position) throw IOException("non-sequential read on stream source")
        var total = 0
        while (total < len) {
            val n = input.read(buf, off + total, len - total)
            if (n < 0) break
            total += n
        }
        if (total == 0 && len > 0) throw IOException("unexpected end of stream")
        position += total
        return total
    }

    override fun close() {
        runCatching { input.close() }
    }
}

/** Plain java.io.File based source (used by tests and for directly readable paths such as APKs). */
class FileListSource(
    files: List<Pair<File, String>>,
    override val groups: List<GroupInfo> = emptyList(),
    kinds: Map<Int, com.starlane.core.proto.FileKind> = emptyMap(),
    groupOf: Map<Int, String> = emptyMap(),
) : TransferSource {
    private val fileList = files.map { it.first }
    override val entries: List<FileEntry> = files.mapIndexed { i, (f, rel) ->
        FileEntry(
            index = i, relPath = rel, size = f.length(), mime = MimeTypes.guess(f.name),
            kind = kinds[i] ?: com.starlane.core.proto.FileKind.FILE, group = groupOf[i] ?: "",
            key = Hex.encode(sha256(f.absolutePath.toByteArray()), 12), lastModified = f.lastModified(),
        )
    }

    override fun open(index: Int): SourceReader = ChannelSourceReader.of(fileList[index])
}

object MimeTypes {
    private val map = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "heic" to "image/heic", "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg", "wav" to "audio/wav", "flac" to "audio/flac",
        "pdf" to "application/pdf", "txt" to "text/plain", "zip" to "application/zip",
        "apk" to "application/vnd.android.package-archive", "obb" to "application/octet-stream",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    fun guess(name: String): String = map[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
}

// ----------------------------------------------------------------------------------------------
// Receiving side
// ----------------------------------------------------------------------------------------------

enum class ConflictPolicy { RENAME, OVERWRITE, SKIP }

sealed class Prepared {
    /** Receive the file; [present] are segments already stored and verified earlier (resume). */
    data class Receive(val resumeKey: String, val present: Map<Int, ByteArray>) : Prepared()

    /** Skip because an identical file already exists (duplicate detection). */
    data class Skip(val reason: String, val existing: String) : Prepared()
}

/** A file being received. Writes are positional and may arrive from several lanes concurrently. */
interface IncomingFile {
    fun write(pos: Long, buf: ByteArray, off: Int, len: Int)

    /** Records a verified segment; persisted (after data is synced) at the next [checkpoint]. */
    fun segmentVerified(seg: Int, hash: ByteArray)
    fun segmentInvalid(seg: Int)

    /** Flushes data to storage and persists the resume state. */
    fun checkpoint()

    /** Finalizes the file at its destination and returns a user-facing location (path or URI). */
    fun commit(): String

    /** Drops the file. With [keepForResume] the partial data and state are kept for a later resume. */
    fun abort(keepForResume: Boolean)
}

data class IncomingContext(val peerId: String, val segmentSize: Int, val policy: ConflictPolicy)

interface ReceiveStorage {
    fun freeBytes(): Long
    fun prepare(ctx: IncomingContext, entry: FileEntry): Prepared
    fun open(ctx: IncomingContext, entry: FileEntry, prepared: Prepared.Receive): IncomingFile
    fun cleanupStale(maxAgeMs: Long) {}
}

/** Strict sanitising of peer-supplied paths: no traversal, no absolute paths, no reserved chars. */
object SafeNames {
    private val reserved = Regex("[\\\\:*?\"<>|\\u0000-\\u001f\\u007f]")
    private const val MAX_COMPONENT = 120
    private const val MAX_DEPTH = 24

    fun sanitizeComponent(name: String): String {
        var s = reserved.replace(name, "_").trim()
        while (s.endsWith('.')) s = s.dropLast(1)
        if (s.isEmpty() || s == "." || s == "..") s = "file"
        if (s.length > MAX_COMPONENT) {
            val ext = s.substringAfterLast('.', "").take(16)
            s = if (ext.isNotEmpty() && ext.length < s.length) s.take(MAX_COMPONENT - ext.length - 1) + "." + ext else s.take(MAX_COMPONENT)
        }
        return s
    }

    fun sanitizeRelPath(path: String): String {
        val parts = path.replace('\\', '/').split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { sanitizeComponent(it) }
        val kept = if (parts.size > MAX_DEPTH) parts.takeLast(MAX_DEPTH) else parts
        return if (kept.isEmpty()) "file" else kept.joinToString("/")
    }

    /** "name.ext" -> "name (n).ext" */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }
}

/** Resume bookkeeping persisted as tiny text files: one per partially received file. */
class ResumeStore(private val dir: File) {
    init { dir.mkdirs() }

    data class State(val partPath: String, val segments: Map<Int, ByteArray>)

    private fun stateFile(key: String) = File(dir, "$key.state")

    @Synchronized
    fun load(key: String): State? {
        val f = stateFile(key)
        if (!f.isFile) return null
        return try {
            val lines = f.readLines()
            if (lines.isEmpty()) return null
            val segs = HashMap<Int, ByteArray>()
            for (l in lines.drop(1)) {
                val sp = l.indexOf(' ')
                if (sp <= 0) continue
                val h = Hex.decode(l.substring(sp + 1))
                if (h.size == 32) segs[l.substring(0, sp).toInt()] = h
            }
            State(lines[0], segs)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun save(key: String, state: State) {
        val tmp = File(dir, "$key.tmp")
        tmp.bufferedWriter().use { w ->
            w.write(state.partPath); w.write("\n")
            for ((s, h) in state.segments) { w.write("$s ${Hex.encode(h)}\n") }
        }
        if (!tmp.renameTo(stateFile(key))) {
            stateFile(key).delete(); tmp.renameTo(stateFile(key))
        }
    }

    @Synchronized
    fun remove(key: String) { stateFile(key).delete() }

    @Synchronized
    fun stale(maxAgeMs: Long): List<Pair<String, State?>> {
        val now = System.currentTimeMillis()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".state") && now - it.lastModified() > maxAgeMs }
            .map { val k = it.name.removeSuffix(".state"); k to load(k) }
    }

    companion object {
        fun keyFor(peerId: String, e: FileEntry, segmentSize: Int): String =
            Hex.encode(sha256("$peerId|${e.key}|${e.size}|${e.lastModified}|$segmentSize|${e.relPath}".toByteArray()), 16)
    }
}

/**
 * Receives into a directory on a regular file system. On Android this is used for
 * Download/Starlane (direct paths to app-owned files are allowed on API 21-28 with storage
 * permission, on 29 with legacy storage and on 30+ for files the app creates itself).
 * Partial files live in a hidden sub-directory on the same volume so commit is an atomic rename.
 */
open class FileSystemStorage(
    protected val root: File,
    private val resume: ResumeStore,
    partsDir: File? = null,
    private val onCommitted: (FileEntry, String) -> Unit = { _, _ -> },
) : ReceiveStorage {
    private val partsDir = partsDir ?: File(root, ".starlane-parts")
    private val claimLock = Any()
    private val claimed = HashSet<String>()

    override fun freeBytes(): Long {
        root.mkdirs()
        return root.usableSpace
    }

    protected open fun targetFor(entry: FileEntry): File = File(root, SafeNames.sanitizeRelPath(entry.relPath))

    /** Alternative destination used when [targetFor] cannot be written (e.g. restricted folders). */
    protected open fun fallbackTargetFor(entry: FileEntry): File? = null

    /**
     * Moves a fully verified partial file to its destination and returns the user-facing location.
     * Subclasses may deliver elsewhere (e.g. a Storage Access Framework folder).
     */
    protected open fun deliver(entry: FileEntry, part: File, policy: ConflictPolicy): String {
        val placed = try {
            place(targetFor(entry), part, policy)
        } catch (e: IOException) {
            val fb = fallbackTargetFor(entry) ?: throw e
            place(fb, part, policy)
        }
        if (entry.lastModified > 0) placed.setLastModified(entry.lastModified)
        return placed.absolutePath
    }

    /** Places [part] at [base] (or a numbered variant) according to the conflict policy. */
    protected fun place(base: File, part: File, policy: ConflictPolicy): File {
        val target = resolveTarget(base, policy)
        try {
            if (policy == ConflictPolicy.OVERWRITE && target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = policy == ConflictPolicy.OVERWRITE)
                part.delete()
            }
        } finally {
            synchronized(claimLock) { claimed -= target.absolutePath }
        }
        return target
    }

    override fun prepare(ctx: IncomingContext, entry: FileEntry): Prepared {
        val key = ResumeStore.keyFor(ctx.peerId, entry, ctx.segmentSize)
        val target = targetFor(entry)
        if (ctx.policy == ConflictPolicy.SKIP && target.isFile && target.length() == entry.size) {
            return Prepared.Skip("duplicate", target.absolutePath)
        }
        val st = resume.load(key)
        val present = if (st != null && File(st.partPath).isFile) st.segments else emptyMap()
        return Prepared.Receive(key, present)
    }

    override fun open(ctx: IncomingContext, entry: FileEntry, prepared: Prepared.Receive): IncomingFile {
        partsDir.mkdirs()
        val part = File(partsDir, "${prepared.resumeKey}.part")
        if (prepared.present.isEmpty()) part.delete()
        val raf = RandomAccessFile(part, "rw")
        if (raf.length() > entry.size) raf.setLength(entry.size)
        return FsIncoming(ctx, entry, prepared, part, raf)
    }

    override fun cleanupStale(maxAgeMs: Long) {
        for ((k, st) in resume.stale(maxAgeMs)) {
            st?.let { File(it.partPath).delete() }
            resume.remove(k)
        }
        val now = System.currentTimeMillis()
        partsDir.listFiles()?.forEach { if (now - it.lastModified() > maxAgeMs) it.delete() }
    }

    /** Finds a free destination name according to the conflict policy. */
    private fun resolveTarget(base: File, policy: ConflictPolicy): File {
        val parent = base.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create directory")
        synchronized(claimLock) {
            if (policy == ConflictPolicy.OVERWRITE) return base
            var f = base
            var n = 1
            while (f.exists() || claimed.contains(f.absolutePath)) {
                f = File(base.parentFile, SafeNames.numbered(base.name, n++))
            }
            claimed += f.absolutePath
            return f
        }
    }

    private inner class FsIncoming(
        val ctx: IncomingContext,
        val entry: FileEntry,
        val prepared: Prepared.Receive,
        val part: File,
        val raf: RandomAccessFile,
    ) : IncomingFile {
        private val ch = raf.channel
        private val verified = HashMap(prepared.present)
        private var dirty = false

        override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
            val bb = ByteBuffer.wrap(buf, off, len)
            var p = pos
            while (bb.hasRemaining()) p += ch.write(bb, p)
        }

        override fun segmentVerified(seg: Int, hash: ByteArray) {
            synchronized(verified) { verified[seg] = hash; dirty = true }
        }

        override fun segmentInvalid(seg: Int) {
            synchronized(verified) { verified.remove(seg); dirty = true }
        }

        override fun checkpoint() {
            val snapshot = synchronized(verified) {
                if (!dirty) return
                dirty = false
                HashMap(verified)
            }
            runCatching { ch.force(false) }
            resume.save(prepared.resumeKey, ResumeStore.State(part.absolutePath, snapshot))
        }

        override fun commit(): String {
            ch.force(false)
            raf.close()
            val location = deliver(entry, part, ctx.policy)
            resume.remove(prepared.resumeKey)
            onCommitted(entry, location)
            return location
        }

        override fun abort(keepForResume: Boolean) {
            if (keepForResume) runCatching { checkpoint() }
            runCatching { raf.close() }
            if (!keepForResume) {
                part.delete()
                resume.remove(prepared.resumeKey)
            }
        }
    }
}
