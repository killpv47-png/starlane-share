package com.starlane.core.proto

import com.starlane.core.util.ProtocolException
import com.starlane.core.util.WireReader
import com.starlane.core.util.WireWriter

const val PROTOCOL_VERSION = 1

/** Record types carried inside the encrypted channel. */
object T {
    const val AUTH = 1
    const val PING = 10
    const val PONG = 11
    const val REQ_SEND = 20
    const val REQ_RECEIVE = 21
    const val RESPONSE = 22
    const val BYE = 23
    const val SESSION = 24
    const val LANE_JOIN = 25
    const val SEG_BEGIN = 30
    const val DATA = 31
    const val SEG_END = 32
    const val SEG_ACK = 33
    const val FILE_COMMITTED = 40
    const val PAUSE = 41
    const val RESUME = 42
    const val CANCEL = 43
    const val COMPLETE = 44
    const val SKIP_FILE = 45
    const val FILE_END = 46
}

enum class FileKind(val id: Int) {
    FILE(0), APK_BASE(1), APK_SPLIT(2), OBB(3), APP_MEDIA(4), APP_EXTRA(5);

    val isApk get() = this == APK_BASE || this == APK_SPLIT

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: FILE
    }
}

/** One file of a transfer, as described to the receiver. Never contains file contents. */
data class FileEntry(
    val index: Int,
    val relPath: String,
    val size: Long,
    val mime: String,
    val kind: FileKind = FileKind.FILE,
    val group: String = "",
    /** Stable sender-side identity (used for resume). Must not reveal private paths. */
    val key: String,
    val lastModified: Long = 0,
    val meta: Map<String, String> = emptyMap(),
) {
    val name: String get() = relPath.substringAfterLast('/')

    fun write(w: WireWriter) {
        w.i32(index).str(relPath).i64(size).str(mime).u8(kind.id).str(group).str(key).i64(lastModified).strMap(meta)
    }

    companion object {
        fun read(r: WireReader): FileEntry {
            val e = FileEntry(
                index = r.i32(), relPath = r.str(2048), size = r.i64(), mime = r.str(256),
                kind = FileKind.of(r.u8()), group = r.str(512), key = r.str(256), lastModified = r.i64(),
                meta = r.strMap(),
            )
            if (e.size < 0) throw ProtocolException("negative size")
            return e
        }
    }
}

/** Group metadata, e.g. an application bundle (APK + splits + OBB + accessible data). */
data class GroupInfo(val id: String, val type: String, val meta: Map<String, String>) {
    fun write(w: WireWriter) { w.str(id).str(type).strMap(meta) }

    companion object {
        const val TYPE_APP = "app"
        fun read(r: WireReader) = GroupInfo(r.str(512), r.str(64), r.strMap(128, 32768))
    }
}

data class Manifest(
    val transferId: String,
    val segmentSize: Int,
    val files: List<FileEntry>,
    val groups: List<GroupInfo> = emptyList(),
) {
    val totalBytes: Long get() = files.sumOf { it.size }

    fun segmentsOf(e: FileEntry): Int = if (e.size == 0L) 0 else ((e.size + segmentSize - 1) / segmentSize).toInt()
    fun segmentLength(e: FileEntry, seg: Int): Int = minOf(segmentSize.toLong(), e.size - seg.toLong() * segmentSize).toInt()

    fun encode(): ByteArray {
        val w = WireWriter(4096).str(transferId).i32(segmentSize).i32(files.size)
        files.forEach { it.write(w) }
        w.u16(groups.size)
        groups.forEach { it.write(w) }
        return w.toByteArray()
    }

    companion object {
        const val MAX_FILES = 20_000
        const val MIN_SEGMENT = 64 * 1024
        const val MAX_SEGMENT = 64 * 1024 * 1024

        fun decode(b: ByteArray): Manifest {
            val r = WireReader(b)
            val id = r.str(64)
            val seg = r.i32()
            if (seg < MIN_SEGMENT || seg > MAX_SEGMENT) throw ProtocolException("bad segment size")
            val n = r.i32()
            if (n < 0 || n > MAX_FILES) throw ProtocolException("too many files")
            val files = ArrayList<FileEntry>(n)
            repeat(n) { i ->
                val e = FileEntry.read(r)
                if (e.index != i) throw ProtocolException("file index mismatch")
                files += e
            }
            val g = r.u16()
            val groups = List(g) { GroupInfo.read(r) }
            return Manifest(id, seg, files, groups)
        }
    }
}

/** Per-file instructions returned by the receiver after accepting. */
data class FilePlan(val index: Int, val receive: Boolean, val present: Map<Int, ByteArray>)

data class Response(
    val id: String,
    val accepted: Boolean,
    val reason: String = "",
    val plans: List<FilePlan> = emptyList(),
    val freeBytes: Long = -1,
) {
    fun encode(): ByteArray {
        val w = WireWriter(256).str(id).bool(accepted).str(reason).i64(freeBytes).i32(plans.size)
        for (p in plans) {
            w.i32(p.index).bool(p.receive).i32(p.present.size)
            p.present.forEach { (seg, h) -> w.i32(seg).raw(h) }
        }
        return w.toByteArray()
    }

    companion object {
        const val REASON_REJECTED = "rejected"
        const val REASON_NO_SPACE = "no_space"
        const val REASON_BUSY = "busy"
        const val REASON_TIMEOUT = "timeout"

        fun decode(b: ByteArray): Response {
            val r = WireReader(b)
            val id = r.str(64); val acc = r.bool(); val reason = r.str(256); val free = r.i64()
            val n = r.i32()
            if (n < 0 || n > Manifest.MAX_FILES) throw ProtocolException("bad plan count")
            val plans = List(n) {
                val idx = r.i32(); val rec = r.bool(); val pc = r.i32()
                if (pc < 0 || pc > 1_000_000) throw ProtocolException("bad present count")
                val m = HashMap<Int, ByteArray>(pc)
                repeat(pc) { m[r.i32()] = r.raw(32) }
                FilePlan(idx, rec, m)
            }
            return Response(id, acc, reason, plans, free)
        }
    }
}

/** Public, user-visible profile announced in discovery and in the handshake. */
data class Profile(
    val username: String,
    val nickname: String,
    val model: String,
    val icon: Int,
    val platform: String,
) {
    fun toMap() = mapOf("u" to username, "n" to nickname, "m" to model, "i" to icon.toString(), "p" to platform)

    companion object {
        fun fromMap(m: Map<String, String>) = Profile(
            username = (m["u"] ?: "").take(40), nickname = (m["n"] ?: "").take(40), model = (m["m"] ?: "").take(60),
            icon = m["i"]?.toIntOrNull() ?: 0, platform = (m["p"] ?: "").take(40),
        )
    }
}

/** Transfer-relevant capabilities. Used by Turbo Mode to pick the best mutually supported settings. */
data class Capabilities(
    val cipherBench: Map<Int, Int>,
    val cores: Int,
    val maxLanes: Int,
    val turbo: Boolean,
    val lowRam: Boolean,
    val listenPort: Int,
    val maxChunk: Int,
) {
    fun toMap(): Map<String, String> = mapOf(
        "cb" to cipherBench.entries.joinToString(",") { "${it.key}:${it.value}" },
        "c" to cores.toString(), "l" to maxLanes.toString(), "t" to (if (turbo) "1" else "0"),
        "r" to (if (lowRam) "1" else "0"), "lp" to listenPort.toString(), "mc" to maxChunk.toString(),
    )

    companion object {
        fun fromMap(m: Map<String, String>): Capabilities {
            val cb = (m["cb"] ?: "").split(',').mapNotNull {
                val p = it.split(':'); if (p.size == 2) p[0].toIntOrNull()?.let { k -> p[1].toIntOrNull()?.let { v -> k to v } } else null
            }.toMap()
            return Capabilities(
                cipherBench = cb, cores = (m["c"]?.toIntOrNull() ?: 1).coerceIn(1, 64),
                maxLanes = (m["l"]?.toIntOrNull() ?: 1).coerceIn(1, 8), turbo = m["t"] == "1", lowRam = m["r"] == "1",
                listenPort = (m["lp"]?.toIntOrNull() ?: 0).coerceIn(0, 65535),
                maxChunk = (m["mc"]?.toIntOrNull() ?: 65536).coerceIn(16 * 1024, 1024 * 1024),
            )
        }
    }
}

/** Authenticated peer, available after a successful handshake. */
data class PeerInfo(
    val deviceId: String,
    val fingerprint: String,
    val profile: Profile,
    val caps: Capabilities,
)
