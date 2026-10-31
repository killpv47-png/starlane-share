package com.starlane.share.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.starlane.core.engine.IncomingRequest
import com.starlane.core.proto.GroupInfo
import com.starlane.share.R
import com.starlane.share.engine.ShareManager
import com.starlane.share.ui.Fmt
import com.starlane.share.ui.GlyphIcon
import com.starlane.share.ui.Palette
import com.starlane.share.ui.PrimaryButton
import com.starlane.share.ui.SecondaryButton
import com.starlane.share.ui.deviceGlyph

/**
 * Incoming request confirmation. Nothing is received until the user taps Accept. Shows who is
 * sending (authenticated username, model, device id), what, how much, and whether it fits.
 */
@Composable
fun IncomingDialog(req: IncomingRequest) {
    val ctx = LocalContext.current
    val t = req.transfer
    val p = t.peer.profile
    val needed = req.bytesNeeded
    val fits = req.freeBytes < 0 || needed + 32L * 1024 * 1024 <= req.freeBytes
    val apps = t.manifest.groups.filter { it.type == GroupInfo.TYPE_APP }

    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Palette.OrbitDeep), contentAlignment = Alignment.Center) {
                    GlyphIcon(deviceGlyph(p.icon), Color.White, 26.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.incoming_title, p.username), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.incoming_device, p.model, p.nickname.ifBlank { p.platform }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.incoming_fingerprint, t.peer.deviceId.chunked(4).joinToString(" ")),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.incoming_summary, pluralStringResource(R.plurals.files_count, t.fileCount, t.fileCount), Fmt.bytes(ctx, t.totalBytes)),
                style = MaterialTheme.typography.titleMedium,
            )
            if (req.isResume) Text(stringResource(R.string.incoming_resume), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            if (apps.isNotEmpty()) {
                Text(
                    stringResource(R.string.incoming_apps, apps.joinToString { it.meta["label"] ?: it.id }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 200.dp)) {
                items(t.manifest.files.take(200), key = { it.index }) { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(f.relPath, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Fmt.bytes(ctx, f.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (req.freeBytes >= 0) {
                Text(
                    if (fits) stringResource(R.string.incoming_space_ok, Fmt.bytes(ctx, req.freeBytes))
                    else stringResource(R.string.incoming_space_low, Fmt.bytes(ctx, needed), Fmt.bytes(ctx, req.freeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.action_reject), { ShareManager.reject(req) }, Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.action_accept), { ShareManager.accept(req) }, Modifier.weight(1f), enabled = fits)
            }
        }
    }
}
