package com.starlane.share.data

import android.content.Context
import com.starlane.core.crypto.Identity
import com.starlane.core.engine.Direction
import com.starlane.core.engine.FileStatus
import com.starlane.core.engine.Transfer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small atomic JSON file helper (write to temp, then rename). All calls happen off the main thread. */
private object JsonFile {
    fun read(f: File): JSONArray = runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
    fun write(f: File, a: JSONArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

/** Long-term identity key, kept in no-backup private storage so it never leaves the device. */
object IdentityStore {
    fun load(context: Context): Identity {
        val dir = File(context.noBackupFilesDir, "identity").apply { mkdirs() }
        val pub = File(dir, "id.pub")
        val key = File(dir, "id.key")
        if (pub.isFile && key.isFile) {
            runCatching { return Identity.fromEncoded(pub.readBytes(), key.readBytes()) }
        }
        val id = Identity.generate()
        key.writeBytes(id.privateEncoded)
        pub.writeBytes(id.publicEncoded)
        return id
    }
}

data class HistoryFile(val name: String, val size: Long, val status: FileStatus, val location: String?, val mime: String, val error: String?)

data class HistoryEntry(
    val id: String,
    val direction: Direction,
    val peerName: String,
    val peerModel: String,
    val peerId: String,
    val method: String,
    val state: String,
    val reason: String,
    val totalBytes: Long,
    val doneBytes: Long,
    val avgSpeed: Double,
    val startedAt: Long,
    val finishedAt: Long,
    val files: List<HistoryFile>,
    val apps: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("dir", direction.name); put("peer", peerName); put("model", peerModel); put("pid", peerId)
        put("method", method); put("state", state); put("reason", reason); put("total", totalBytes); put("done", doneBytes)
        put("avg", avgSpeed); put("start", startedAt); put("end", finishedAt)
        put("apps", JSONArray(apps))
        put("files", JSONArray().apply {
            files.forEach { f ->
                put(JSONObject().apply {
                    put("n", f.name); put("s", f.size); put("st", f.status.name); put("l", f.location ?: ""); put("m", f.mime)
                    put("e", f.error ?: "")
                })
            }
        })
    }

    companion object {
        /** History keeps at most this many file rows per entry to bound storage and memory. */
        const val MAX_FILES = 500

        fun from(t: Transfer, avgSpeed: Double): HistoryEntry = HistoryEntry(
            id = t.id, direction = t.direction, peerName = t.peer.profile.username, peerModel = t.peer.profile.model,
            peerId = t.peer.deviceId, method = t.linkKind.name, state = t.state.name, reason = t.reason,
            totalBytes = t.totalBytes, doneBytes = t.doneBytes, avgSpeed = avgSpeed,
            startedAt = if (t.startedAt > 0) t.startedAt else System.currentTimeMillis(),
            finishedAt = if (t.finishedAt > 0) t.finishedAt else System.currentTimeMillis(),
            files = t.manifest.files.take(MAX_FILES).map { e ->
                HistoryFile(e.name, e.size, t.fileStatus[e.index], t.fileLocation[e.index], e.mime, t.fileError[e.index])
            },
            apps = t.manifest.groups.map { it.meta["label"] ?: it.id },
        )

        fun fromJson(o: JSONObject): HistoryEntry {
            val fa = o.optJSONArray("files") ?: JSONArray()
            val aa = o.optJSONArray("apps") ?: JSONArray()
            return HistoryEntry(
                o.optString("id"), runCatching { Direction.valueOf(o.optString("dir")) }.getOrDefault(Direction.SEND),
                o.optString("peer"), o.optString("model"), o.optString("pid"), o.optString("method"), o.optString("state"),
                o.optString("reason"), o.optLong("total"), o.optLong("done"), o.optDouble("avg", 0.0),
                o.optLong("start"), o.optLong("end"),
                List(fa.length()) { i ->
                    val f = fa.getJSONObject(i)
                    HistoryFile(
                        f.optString("n"), f.optLong("s"), runCatching { FileStatus.valueOf(f.optString("st")) }.getOrDefault(FileStatus.FAILED),
                        f.optString("l").ifEmpty { null }, f.optString("m"), f.optString("e").ifEmpty { null },
                    )
                },
                List(aa.length()) { aa.getString(it) },
            )
        }
    }
}

class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val lock = Any()

    fun all(): List<HistoryEntry> = synchronized(lock) {
        val a = JsonFile.read(file)
        List(a.length()) { HistoryEntry.fromJson(a.getJSONObject(it)) }
    }

    fun add(e: HistoryEntry) = synchronized(lock) {
        val list = all().filter { it.id != e.id }.toMutableList()
        list.add(0, e)
        JsonFile.write(file, JSONArray().apply { list.take(MAX_ENTRIES).forEach { put(it.toJson()) } })
    }

    fun clear() = synchronized(lock) { file.delete(); Unit }

    companion object { const val MAX_ENTRIES = 300 }
}

data class KnownDevice(
    val deviceId: String,
    val username: String,
    val nickname: String,
    val model: String,
    val icon: Int,
    val favorite: Boolean,
    val lastSeen: Long,
    val lastMethod: String,
    val transfers: Int,
) {
    fun toJson() = JSONObject().apply {
        put("id", deviceId); put("u", username); put("n", nickname); put("m", model); put("i", icon)
        put("f", favorite); put("t", lastSeen); put("lm", lastMethod); put("c", transfers)
    }

    companion object {
        fun fromJson(o: JSONObject) = KnownDevice(
            o.optString("id"), o.optString("u"), o.optString("n"), o.optString("m"), o.optInt("i"),
            o.optBoolean("f"), o.optLong("t"), o.optString("lm"), o.optInt("c"),
        )
    }
}

/** Recent and favourite devices (only devices this phone actually connected to and authenticated). */
class DeviceStore(context: Context) {
    private val file = File(context.filesDir, "devices.json")
    private val lock = Any()

    fun all(): List<KnownDevice> = synchronized(lock) {
        val a = JsonFile.read(file)
        List(a.length()) { KnownDevice.fromJson(a.getJSONObject(it)) }
            .sortedWith(compareByDescending<KnownDevice> { it.favorite }.thenByDescending { it.lastSeen })
    }

    private fun save(list: List<KnownDevice>) =
        JsonFile.write(file, JSONArray().apply { list.take(100).forEach { put(it.toJson()) } })

    fun touch(id: String, username: String, nickname: String, model: String, icon: Int, method: String, transferred: Boolean) =
        synchronized(lock) {
            val list = all().toMutableList()
            val old = list.firstOrNull { it.deviceId == id }
            list.removeAll { it.deviceId == id }
            list.add(
                0, KnownDevice(
                    id, username, nickname, model, icon, old?.favorite ?: false, System.currentTimeMillis(), method,
                    (old?.transfers ?: 0) + if (transferred) 1 else 0,
                ),
            )
            save(list)
        }

    fun setFavorite(id: String, fav: Boolean) = synchronized(lock) {
        save(all().map { if (it.deviceId == id) it.copy(favorite = fav) else it })
    }

    fun remove(id: String) = synchronized(lock) { save(all().filter { it.deviceId != id }) }

    fun clearRecents() = synchronized(lock) { save(all().filter { it.favorite }) }
}
