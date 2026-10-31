package com.starlane.share.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.starlane.core.engine.Direction
import com.starlane.core.engine.FileStatus
import com.starlane.core.engine.TransferState
import com.starlane.share.R
import com.starlane.share.apps.InstallEvents
import com.starlane.share.apps.Installer
import com.starlane.share.apps.ReceivedApp
import com.starlane.share.apps.ReceivedApps
import com.starlane.share.engine.ShareManager
import com.starlane.share.engine.TransferUi
import com.starlane.share.ui.EmptyState
import com.starlane.share.ui.Fmt
import com.starlane.share.ui.Glyph
import com.starlane.share.ui.GlyphIcon
import com.starlane.share.ui.Palette
import com.starlane.share.ui.ProgressBar
import com.starlane.share.ui.SecondaryButton
import com.starlane.share.ui.SectionCard
import com.starlane.share.ui.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Transfer queue: live progress, pause/resume, cancel, retry, per-file report, app installation. */
@Composable
fun TransfersScreen(onBack: () -> Unit) {
    val list by ShareManager.transfers.collectAsState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        TopBar(stringResource(R.string.transfer_title), onBack) {
            if (list.any { it.state.terminal }) {
                Text(stringResource(R.string.transfer_clear_finished), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ShareManager.clearFinished() }.padding(8.dp))
            }
        }
        StatusPanel(onOpenTransfers = {}).let { }
        if (list.isEmpty()) { EmptyState(Glyph.SEND, stringResource(R.string.transfer_none)); return@Column }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { t -> TransferCard(t) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun stateText(t: TransferUi): String = when {
    t.recovering -> stringResource(R.string.transfer_recovering)
    t.paused && !t.state.terminal -> stringResource(R.string.transfer_paused)
    else -> when (t.state) {
        TransferState.WAITING_ACCEPT, TransferState.QUEUED -> stringResource(R.string.transfer_waiting_accept)
        TransferState.RUNNING -> stringResource(R.string.transfer_files_progress, t.doneFiles + t.skippedFiles, t.fileCount)
        TransferState.COMPLETED -> stringResource(if (t.reason.isEmpty()) R.string.transfer_completed else R.string.transfer_completed_partial)
        TransferState.FAILED -> stringResource(R.string.transfer_failed)
        TransferState.CANCELLED -> stringResource(R.string.transfer_cancelled)
        TransferState.REJECTED -> stringResource(R.string.transfer_rejected)
        TransferState.INTERRUPTED -> stringResource(R.string.transfer_interrupted)
    }
}

@Composable
fun reasonText(reason: String): String? {
    val id = when (reason) {
        "rejected" -> R.string.reason_rejected; "no_space" -> R.string.reason_no_space; "busy" -> R.string.reason_busy
        "timeout" -> R.string.reason_timeout; "cancelled_by_peer" -> R.string.reason_cancelled_by_peer; "cancelled" -> R.string.reason_cancelled
        "connection_lost" -> R.string.reason_connection_lost; "integrity" -> R.string.reason_integrity; "source_error" -> R.string.reason_source_error
        "storage_error" -> R.string.reason_storage_error; "partial" -> R.string.reason_partial
        else -> return null
    }
    return stringResource(id)
}

@Composable
private fun TransferCard(t: TransferUi) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    SectionCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(if (t.direction == Direction.SEND) Glyph.SEND else Glyph.RECEIVE, cs.primary, 20.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (t.direction == Direction.SEND) R.string.transfer_sending else R.string.transfer_receiving, t.peerName),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("${methodLabel(t.method.name)} · ${pluralStringResource(R.plurals.files_count, t.fileCount, t.fileCount)} · ${Fmt.bytes(ctx, t.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
                TurboBadge(t.turbo)
            }
            Spacer(Modifier.height(10.dp))
            val color = when (t.state) {
                TransferState.COMPLETED -> if (t.reason.isEmpty()) Palette.Aurora else Palette.Star
                TransferState.FAILED, TransferState.CANCELLED, TransferState.REJECTED -> cs.error
                else -> cs.primary
            }
            ProgressBar(t.progress, color = color)
            Spacer(Modifier.height(6.dp))
            Row {
                Text(stateText(t), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("${Fmt.bytes(ctx, t.doneBytes)} / ${Fmt.bytes(ctx, t.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (t.state == TransferState.RUNNING && !t.paused) {
                Text("${Fmt.speed(ctx, t.speed)} · ${stringResource(R.string.status_avg)} ${Fmt.speed(ctx, t.avgSpeed)} · ${Fmt.duration(ctx, t.etaSec)}",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (t.turboNote != com.starlane.core.engine.TurboNote.NONE) {
                Text(turboNoteText(t.turboNote), style = MaterialTheme.typography.bodySmall, color = cs.tertiary)
            }
            if (t.state.terminal || t.state == TransferState.INTERRUPTED) reasonText(t.reason)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (t.state == TransferState.COMPLETED) cs.tertiary else cs.error)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!t.state.terminal && t.state == TransferState.RUNNING) {
                    SecondaryButton(stringResource(if (t.paused) R.string.action_resume else R.string.action_pause), { ShareManager.pause(t.id, !t.paused) },
                        glyph = if (t.paused) Glyph.PLAY else Glyph.PAUSE)
                }
                if (!t.state.terminal) SecondaryButton(stringResource(R.string.action_cancel), { ShareManager.cancel(t.id) }, glyph = Glyph.CLOSE)
                if (t.resumable) SecondaryButton(stringResource(R.string.action_retry), { ShareManager.retry(t.id) }, glyph = Glyph.RETRY)
            }
            if (t.state == TransferState.COMPLETED) Report(t)
        }
    }
}

@Composable
fun turboNoteText(n: com.starlane.core.engine.TurboNote): String = stringResource(
    when (n) {
        com.starlane.core.engine.TurboNote.LOCAL_LOW_END -> R.string.turbo_note_LOCAL_LOW_END
        com.starlane.core.engine.TurboNote.PEER_NO_TURBO -> R.string.turbo_note_PEER_NO_TURBO
        com.starlane.core.engine.TurboNote.PEER_LOW_END -> R.string.turbo_note_PEER_LOW_END
        com.starlane.core.engine.TurboNote.LINK_BLUETOOTH -> R.string.turbo_note_LINK_BLUETOOTH
        com.starlane.core.engine.TurboNote.LINK_USB -> R.string.turbo_note_LINK_USB
        com.starlane.core.engine.TurboNote.NONE -> R.string.turbo_note_NONE
    },
)

/** Post-transfer report: verified, skipped, failed files; received apps with install actions. */
@Composable
private fun Report(t: TransferUi) {
    val ctx = LocalContext.current
    val transfer = ShareManager.transfer(t.id) ?: return
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.transfer_report), style = MaterialTheme.typography.labelLarge, color = cs.primary)
    Text(stringResource(R.string.transfer_report_ok, pluralStringResource(R.plurals.files_count, t.doneFiles, t.doneFiles)), style = MaterialTheme.typography.bodySmall)
    if (t.skippedFiles > 0) Text(stringResource(R.string.transfer_report_skipped, pluralStringResource(R.plurals.files_count, t.skippedFiles, t.skippedFiles)), style = MaterialTheme.typography.bodySmall)
    if (t.failedFiles > 0) Text(stringResource(R.string.transfer_report_failed, pluralStringResource(R.plurals.files_count, t.failedFiles, t.failedFiles)), style = MaterialTheme.typography.bodySmall, color = cs.error)

    // Received files (receiver side): open / copy info.
    if (t.direction == Direction.RECEIVE) {
        val files = transfer.manifest.files.filter { transfer.fileStatus[it.index] == FileStatus.DONE && !it.kind.isApk }.take(20)
        files.forEach { f ->
            val loc = transfer.fileLocation[f.index] ?: return@forEach
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.action_open), color = cs.primary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { openFile(ctx, loc, f.mime) }.padding(6.dp))
                Text(stringResource(R.string.action_copy_info), color = cs.primary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { copyInfo(ctx, "${f.name}\n${f.size} B\n${f.mime}\n$loc") }.padding(6.dp))
            }
        }
        val apps = remember(t.id) { ReceivedApps.from(transfer) }
        if (apps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.transfer_received_apps), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            apps.forEach { InstallRow(it) }
        }
    }
}

@Composable
private fun InstallRow(app: ReceivedApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = remember { mutableStateMapOf<String, String>() }
    DisposableEffect(app.packageName) {
        InstallEvents.listener = { r ->
            status[r.pkg] = when {
                r.success -> ctx.getString(R.string.install_ok, app.label)
                r.aborted -> ctx.getString(R.string.install_aborted)
                else -> ctx.getString(R.string.install_failed, r.message)
            }
        }
        onDispose { InstallEvents.listener = null }
    }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.transfer_app_report, "${app.label} ${app.versionName}", Fmt.number(ctx, app.extraOk)), style = MaterialTheme.typography.bodyMedium)
        if (app.inaccessible.isNotEmpty()) Text(stringResource(R.string.transfer_app_inaccessible, app.inaccessible.joinToString()), style = MaterialTheme.typography.bodySmall, color = cs.error)
        Text(stringResource(R.string.transfer_app_private), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        if (app.obbCount > 0) Text(stringResource(R.string.install_obb_note), style = MaterialTheme.typography.bodySmall, color = cs.tertiary)
        status[app.packageName]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.secondary) }
        if (app.packageComplete) {
            Spacer(Modifier.height(4.dp))
            SecondaryButton(stringResource(R.string.action_install), {
                if (!Installer.canRequestInstalls(ctx)) {
                    Toast.makeText(ctx, ctx.getString(R.string.install_need_permission), Toast.LENGTH_LONG).show()
                    runCatching { ctx.startActivity(Installer.unknownSourcesIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    return@SecondaryButton
                }
                status[app.packageName] = ctx.getString(R.string.install_checking)
                scope.launch {
                    val apks = app.apks.map { (n, loc) -> n to ReceivedApps.opener(ctx, loc) }
                    val check = withContext(Dispatchers.IO) { Installer.check(ctx, apks, ctx.cacheDir) }
                    if (!check.ok) {
                        status[app.packageName] = problemText(ctx, check)
                        return@launch
                    }
                    val r = withContext(Dispatchers.IO) { runCatching { Installer.install(ctx, check.packageName, apks, app.apkSizes) } }
                    status[app.packageName] = if (r.isSuccess) ctx.getString(R.string.install_waiting) else ctx.getString(R.string.install_failed, r.exceptionOrNull()?.javaClass?.simpleName ?: "")
                }
            }, glyph = Glyph.DOWNLOAD)
        } else {
            Text(ctx.getString(R.string.install_problem_MISSING_SPLITS, "${app.expectedApks - app.apks.size}"), style = MaterialTheme.typography.bodySmall, color = cs.error)
        }
    }
}

private fun problemText(ctx: Context, c: com.starlane.share.apps.BundleCheck): String = when (c.problem) {
    com.starlane.share.apps.BundleCheck.Problem.NO_BASE -> ctx.getString(R.string.install_problem_NO_BASE)
    com.starlane.share.apps.BundleCheck.Problem.NOT_AN_APK -> ctx.getString(R.string.install_problem_NOT_AN_APK)
    com.starlane.share.apps.BundleCheck.Problem.PACKAGE_MISMATCH -> ctx.getString(R.string.install_problem_PACKAGE_MISMATCH)
    com.starlane.share.apps.BundleCheck.Problem.MISSING_SPLITS -> ctx.getString(R.string.install_problem_MISSING_SPLITS, c.missingSplits.joinToString())
    com.starlane.share.apps.BundleCheck.Problem.SDK_TOO_OLD -> ctx.getString(R.string.install_problem_SDK_TOO_OLD)
    else -> ctx.getString(R.string.install_problem_UNREADABLE)
}

fun openFile(ctx: Context, location: String, mime: String) {
    val uri: Uri = if (location.startsWith("content://")) Uri.parse(location)
    else runCatching { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(location)) }.getOrNull() ?: return
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun copyInfo(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Starlane", text))
    Toast.makeText(ctx, ctx.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}
