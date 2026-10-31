package com.starlane.share.apps

import android.content.Context
import android.net.Uri
import com.starlane.core.engine.FileStatus
import com.starlane.core.engine.Transfer
import com.starlane.core.proto.FileKind
import com.starlane.core.proto.GroupInfo
import java.io.File
import java.io.InputStream

/** Per-app result of a received transfer, used for the post-transfer report and installation. */
data class ReceivedApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    /** (display name, location) of every APK file that arrived and verified. */
    val apks: List<Pair<String, String>>,
    val apkSizes: List<Long>,
    val expectedApks: Int,
    val extraOk: Int,
    val extraFailed: Int,
    val obbCount: Int,
    val inaccessible: List<String>,
) {
    val packageComplete: Boolean get() = apks.size == expectedApks && expectedApks > 0
}

object ReceivedApps {
    fun from(t: Transfer): List<ReceivedApp> = t.manifest.groups.filter { it.type == GroupInfo.TYPE_APP }.map { g -> build(t, g) }

    private fun build(t: Transfer, g: GroupInfo): ReceivedApp {
        val files = t.manifest.files.filter { it.group == g.id }
        val apkFiles = files.filter { it.kind.isApk }.sortedBy { if (it.kind == FileKind.APK_BASE) 0 else 1 }
        val okApks = apkFiles.filter { t.fileStatus[it.index] == FileStatus.DONE && t.fileLocation[it.index] != null }
        val extras = files.filter { !it.kind.isApk }
        return ReceivedApp(
            packageName = g.id, label = g.meta["label"] ?: g.id, versionName = g.meta["versionName"] ?: "",
            apks = okApks.map { it.name to t.fileLocation[it.index]!! }, apkSizes = okApks.map { it.size },
            expectedApks = apkFiles.size,
            extraOk = extras.count { t.fileStatus[it.index] == FileStatus.DONE || t.fileStatus[it.index] == FileStatus.SKIPPED },
            extraFailed = extras.count { t.fileStatus[it.index] == FileStatus.FAILED },
            obbCount = extras.count { it.kind == FileKind.OBB },
            inaccessible = (g.meta["inaccessible"] ?: "").split('|').filter { it.isNotBlank() },
        )
    }

    /** Opens a received file whether it was stored as a path or a content Uri. */
    fun opener(context: Context, location: String): () -> InputStream = {
        if (location.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(location))!!
        else File(location).inputStream()
    }
}
