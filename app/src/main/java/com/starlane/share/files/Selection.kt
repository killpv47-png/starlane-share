package com.starlane.share.files

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.starlane.core.proto.GroupInfo

/**
 * The user's current selection, shared by the picker, app-detail screen and Sharesheet entry.
 * Items are keyed so the same file cannot be added twice.
 */
object Selection {
    val items = mutableStateListOf<PickItem>()
    /** Apps (package name -> plan items + group) selected for sending. */
    val apps = mutableStateMapOf<String, Pair<List<PickItem>, GroupInfo>>()

    private fun keyOf(p: PickItem) = p.uri?.toString() ?: p.file?.absolutePath ?: p.relPath

    fun contains(p: PickItem) = items.any { keyOf(it) == keyOf(p) }

    fun toggle(p: PickItem) {
        val k = keyOf(p)
        val i = items.indexOfFirst { keyOf(it) == k }
        if (i >= 0) items.removeAt(i) else items.add(p)
    }

    fun addAll(list: List<PickItem>) {
        val existing = items.map { keyOf(it) }.toHashSet()
        list.forEach { if (existing.add(keyOf(it))) items.add(it) }
    }

    fun removeAll(list: List<PickItem>) {
        val keys = list.map { keyOf(it) }.toHashSet()
        items.removeAll { keyOf(it) in keys }
    }

    fun clear() { items.clear(); apps.clear() }

    val count: Int get() = items.size + apps.size
    val totalBytes: Long get() = items.sumOf { it.size } + apps.values.sumOf { v -> v.first.sumOf { it.size } }
    val isEmpty: Boolean get() = items.isEmpty() && apps.isEmpty()

    /** Flattens files + app bundles into one transfer. */
    fun build(): Pair<List<PickItem>, List<GroupInfo>> {
        val all = ArrayList<PickItem>(items)
        val groups = ArrayList<GroupInfo>()
        apps.values.forEach { (its, g) -> all.addAll(its); groups.add(g) }
        return dedupePaths(all) to groups
    }

    /** Two different files with the same name would collide on the receiver; make relative paths unique. */
    private fun dedupePaths(list: List<PickItem>): List<PickItem> {
        val seen = HashMap<String, Int>()
        return list.map { p ->
            val n = seen[p.relPath.lowercase()] ?: 0
            seen[p.relPath.lowercase()] = n + 1
            if (n == 0) p else {
                val dot = p.relPath.lastIndexOf('.')
                val slash = p.relPath.lastIndexOf('/')
                val rel = if (dot > slash + 1) "${p.relPath.substring(0, dot)} ($n)${p.relPath.substring(dot)}" else "${p.relPath} ($n)"
                p.copy(relPath = rel)
            }
        }
    }
}
