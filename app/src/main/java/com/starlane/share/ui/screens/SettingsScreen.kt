package com.starlane.share.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.starlane.core.engine.ConflictPolicy
import com.starlane.share.BuildConfig
import com.starlane.share.R
import com.starlane.share.engine.ShareManager
import com.starlane.share.ui.Chip
import com.starlane.share.ui.Fmt
import com.starlane.share.ui.SectionCard
import com.starlane.share.ui.SectionTitle
import com.starlane.share.ui.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings. Every control changes real behaviour:
 *  - language / theme / reduce motion: applied immediately by MainActivity
 *  - Turbo: used by the tuner for every new transfer
 *  - conflict policy: passed to the receiver when accepting
 *  - save location: used by AndroidStorage when committing received files
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onLanguageChanged: () -> Unit, onAppearanceChanged: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ShareManager.prefs
    var lang by remember { mutableStateOf(prefs.language) }
    var theme by remember { mutableStateOf(prefs.theme) }
    var reduce by remember { mutableStateOf(prefs.reduceAnimations) }
    var turbo by remember { mutableStateOf(prefs.turbo) }
    var conflict by remember { mutableStateOf(prefs.conflictPolicy) }
    var tree by remember { mutableStateOf(prefs.saveTreeUri) }
    var editProfile by remember { mutableStateOf(false) }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            prefs.saveTreeUri = uri.toString(); tree = uri.toString()
        }
    }
    val caps by produceState<ShareManager.Capability?>(null) { value = withContext(Dispatchers.IO) { ShareManager.capabilitySummary() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(stringResource(R.string.settings_title), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle(stringResource(R.string.settings_profile))
            SectionCard {
                if (editProfile) ProfileEditor(initialEdit = false) { editProfile = false }
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(prefs.username, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(prefs.nickname, ShareManager.deviceModel()).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.action_review), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { editProfile = true }.padding(8.dp))
                }
            }

            SectionTitle(stringResource(R.string.settings_transfer))
            SectionCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.turbo_mode), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.turbo_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(turbo, { turbo = it; prefs.turbo = it })
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_conflict), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConflictPolicy.entries.forEach { p ->
                            val label = when (p) {
                                ConflictPolicy.RENAME -> R.string.conflict_RENAME
                                ConflictPolicy.OVERWRITE -> R.string.conflict_OVERWRITE
                                ConflictPolicy.SKIP -> R.string.conflict_SKIP
                            }
                            Chip(stringResource(label), conflict == p, { conflict = p; prefs.conflictPolicy = p })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_save_location), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tree?.let { Uri.parse(it).lastPathSegment?.substringAfter(':') ?: it } ?: stringResource(R.string.settings_save_default),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.settings_save_choose), false, { treeLauncher.launch(null) })
                        if (tree != null) Chip(stringResource(R.string.settings_save_reset), false, { prefs.saveTreeUri = null; tree = null })
                    }
                }
            }

            SectionTitle(stringResource(R.string.settings_appearance))
            SectionCard {
                Column {
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to R.string.lang_system, "en" to R.string.lang_en, "fa" to R.string.lang_fa).forEach { (code, label) ->
                            Chip(stringResource(label), lang == code, {
                                if (lang != code) { lang = code; prefs.language = code; onLanguageChanged() }
                            })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to R.string.theme_system, 1 to R.string.theme_light, 2 to R.string.theme_dark).forEach { (v, label) ->
                            Chip(stringResource(label), theme == v, { theme = v; prefs.theme = v; onAppearanceChanged() })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_reduce_motion), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.settings_reduce_motion_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(reduce, { reduce = it; prefs.reduceAnimations = it; onAppearanceChanged() })
                    }
                }
            }

            SectionTitle(stringResource(R.string.settings_capabilities))
            SectionCard {
                val c = caps
                if (c == null) Text(stringResource(R.string.pick_loading))
                else Column {
                    CapRow(stringResource(R.string.cap_wifi_direct), c.wifiDirect)
                    CapRow(stringResource(R.string.cap_wifi_aware), c.wifiAware)
                    CapRow(stringResource(R.string.cap_bluetooth), c.bluetooth)
                    CapRow(stringResource(R.string.cap_usb_host), c.usbHost)
                    CapRow(stringResource(R.string.cap_usb_accessory), c.usbAccessory)
                    Spacer(Modifier.height(6.dp))
                    Text("${stringResource(R.string.cap_class)}: ${stringResource(if (c.lowEnd) R.string.cap_low_end else R.string.cap_standard)}", style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.cap_cpu, Fmt.number(ctx, c.cores), Fmt.bytes(ctx, c.ramMb * 1024 * 1024)), style = MaterialTheme.typography.bodyMedium)
                    Text("${stringResource(R.string.cap_cipher)}: " + c.cipherBench.entries.joinToString { "${it.key.replace('_', '-')} ${Fmt.number(ctx, it.value)} MB/s" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.wifiAware) Text(stringResource(R.string.cap_aware_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionTitle(stringResource(R.string.settings_about))
            SectionCard {
                Column {
                    Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.settings_open_source), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/killpv47-png/starlane-share"))) }
                        }.padding(vertical = 8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CapRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(if (ok) R.string.cap_supported else R.string.cap_unsupported),
            color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
