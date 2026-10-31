package com.starlane.share.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starlane.share.R
import com.starlane.share.apps.AppEntry
import com.starlane.share.apps.AppPackages
import com.starlane.share.apps.AppPlan
import com.starlane.share.files.MediaQuery
import com.starlane.share.files.PickItem
import com.starlane.share.files.Selection
import com.starlane.share.ui.Fmt
import com.starlane.share.ui.Glyph
import com.starlane.share.ui.GlyphIcon
import com.starlane.share.ui.PrimaryButton
import com.starlane.share.ui.SecondaryButton
import com.starlane.share.ui.SectionCard
import com.starlane.share.ui.SectionTitle
import com.starlane.share.ui.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App transfer detail with the DATA toggle. The plan is computed from what Android actually lets
 * this app read, and the screen states plainly what will NOT be transferred and why.
 */
@Composable
fun AppDetailScreen(app: AppEntry, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var withData by remember { mutableStateOf(true) }
    val extras = remember { mutableStateListOf<PickItem>() }
    val plan by produceState<AppPlan?>(null, withData, extras.size) {
        value = withContext(Dispatchers.IO) { runCatching { AppPackages.plan(ctx, app, withData, extras.toList()) }.getOrNull() }
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch { extras.addAll(withContext(Dispatchers.IO) { MediaQuery.listTree(ctx, uri) }) }
    }
    val px = with(LocalDensity.current) { 56.dp.roundToPx() }
    val icon by produceState<android.graphics.Bitmap?>(null, app.packageName) { value = withContext(Dispatchers.IO) { AppPackages.icon(ctx, app.packageName, px) } }
    val selected = Selection.apps.containsKey(app.packageName)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        TopBar(stringResource(R.string.app_detail_title), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Image(it.asImageBitmap(), app.label, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))) }
                    ?: GlyphIcon(Glyph.APP, MaterialTheme.colorScheme.primary, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(app.label, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.app_version, app.versionName, Fmt.bytes(ctx, app.apkSize)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(app.packageName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (app.splitCount > 0) stringResource(R.string.app_splits, app.splitCount) else stringResource(R.string.app_single),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_data_toggle), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.app_data_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(withData, { withData = it })
                }
            }
            val p = plan
            if (p == null) {
                Text(stringResource(R.string.pick_loading), Modifier.padding(16.dp))
            } else {
                SectionTitle(stringResource(R.string.app_will_include))
                SectionCard {
                    Column {
                        PlanRow(Glyph.CHECK, stringResource(R.string.app_item_apk, Fmt.bytes(ctx, p.apkBytes)))
                        if (withData) {
                            if (p.obbFiles.isNotEmpty()) PlanRow(Glyph.CHECK, stringResource(R.string.app_item_obb, Fmt.number(ctx, p.obbFiles.size), Fmt.bytes(ctx, p.obbBytes)))
                            if (p.mediaFiles.isNotEmpty()) PlanRow(Glyph.CHECK, stringResource(R.string.app_item_media, Fmt.number(ctx, p.mediaFiles.size), Fmt.bytes(ctx, p.mediaBytes)))
                            if (p.extraItems.isNotEmpty()) PlanRow(Glyph.CHECK, stringResource(R.string.app_item_extra, Fmt.number(ctx, p.extraItems.size), Fmt.bytes(ctx, p.extraBytes)))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.app_total, Fmt.bytes(ctx, p.totalBytes)), style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (withData) {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(stringResource(R.string.app_add_extra), { treeLauncher.launch(null) }, Modifier.fillMaxWidth(), glyph = Glyph.FOLDER)
                    Text(stringResource(R.string.app_add_extra_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                }
                SectionTitle(stringResource(R.string.app_cannot_include))
                SectionCard {
                    Column {
                        p.inaccessible.forEach { PlanRow(Glyph.LOCK, stringResource(R.string.app_blocked, it), MaterialTheme.colorScheme.error) }
                        PlanRow(Glyph.LOCK, stringResource(R.string.app_private_note), MaterialTheme.colorScheme.error)
                        PlanRow(Glyph.WIFI, stringResource(R.string.app_server_note), MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
            PrimaryButton(
                stringResource(if (selected) R.string.app_remove_from_selection else R.string.app_add_to_selection),
                {
                    val pl = plan ?: return@PrimaryButton
                    if (selected) Selection.apps.remove(app.packageName)
                    else Selection.apps[app.packageName] = AppPackages.toItems(pl)
                    onBack()
                },
                Modifier.fillMaxWidth(), enabled = plan != null, glyph = if (selected) Glyph.CLOSE else Glyph.CHECK,
            )
        }
    }
}

@Composable
private fun PlanRow(g: Glyph, text: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.secondary) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        GlyphIcon(g, tint, 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
