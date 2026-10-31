package com.starlane.share.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starlane.core.engine.Direction
import com.starlane.core.engine.FileStatus
import com.starlane.share.R
import com.starlane.share.data.HistoryEntry
import com.starlane.share.data.KnownDevice
import com.starlane.share.engine.ShareManager
import com.starlane.share.net.UsbTransport
import com.starlane.share.ui.EmptyState
import com.starlane.share.ui.Fmt
import com.starlane.share.ui.Glyph
import com.starlane.share.ui.GlyphIcon
import com.starlane.share.ui.Palette
import com.starlane.share.ui.PrimaryButton
import com.starlane.share.ui.SecondaryButton
import com.starlane.share.ui.SectionCard
import com.starlane.share.ui.SectionTitle
import com.starlane.share.ui.TopBar
import com.starlane.share.ui.deviceGlyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------------------------------------
// History
// ------------------------------------------------------------------------------------------------
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val entries by produceState<List<HistoryEntry>?>(null, version) { value = withContext(Dispatchers.IO) { ShareManager.history.all() } }
    var expanded by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        TopBar(stringResource(R.string.history_title), onBack) {
            if (!entries.isNullOrEmpty()) {
                Text(
                    stringResource(R.string.history_clear), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        scope.launch {
                            withContext(Dispatchers.IO) { ShareManager.history.clear() }
                            version++
                        }
                    }.padding(8.dp),
                )
            }
        }
        val list = entries
        when {
            list == null -> Text(stringResource(R.string.pick_loading), Modifier.padding(24.dp))
            list.isEmpty() -> EmptyState(Glyph.HISTORY, stringResource(R.string.history_empty))
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { e ->
                    SectionCard(Modifier.clickable { expanded = if (expanded == e.id) null else e.id }) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GlyphIcon(if (e.direction == Direction.SEND) Glyph.SEND else Glyph.RECEIVE, MaterialTheme.colorScheme.primary, 20.dp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(if (e.direction == Direction.SEND) R.string.history_sent else R.string.history_received, e.peerName),
                                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${Fmt.dateTime(ctx, e.finishedAt)} · ${methodLabel(e.method)}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${pluralStringResource(R.plurals.files_count, e.files.size, e.files.size)} · ${Fmt.bytes(ctx, e.totalBytes)} · " +
                                    stringResource(R.string.history_avg, Fmt.speed(ctx, e.avgSpeed)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            reasonText(e.reason)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (expanded == e.id) {
                                Spacer(Modifier.height(6.dp))
                                e.files.take(100).forEach { f ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val ok = f.status == FileStatus.DONE || f.status == FileStatus.SKIPPED
                                        GlyphIcon(if (ok) Glyph.CHECK else Glyph.CLOSE, if (ok) Palette.Aurora else MaterialTheme.colorScheme.error, 14.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val loc = f.location
                                        if (e.direction == Direction.RECEIVE && loc != null && f.status == FileStatus.DONE) {
                                            Text(
                                                stringResource(R.string.action_open), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { openFile(ctx, loc, f.mime) }.padding(6.dp),
                                            )
                                        }
                                        Text(
                                            stringResource(R.string.action_copy_info), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                                .clickable { copyInfo(ctx, "${f.name}\n${f.size} B\n${f.mime}\n${loc ?: ""}") }.padding(6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Devices (recent & favourites)
// ------------------------------------------------------------------------------------------------
@Composable
fun DevicesScreen(onBack: () -> Unit, onScan: () -> Unit) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val devices by produceState<List<KnownDevice>?>(null, version) { value = withContext(Dispatchers.IO) { ShareManager.devices.all() } }
    val conn by ShareManager.connection.collectAsState()
    fun io(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            version++
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        TopBar(stringResource(R.string.devices_title), onBack) {
            if (devices?.any { !it.favorite } == true) {
                Text(
                    stringResource(R.string.devices_clear_recent), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { io { ShareManager.devices.clearRecents() } }.padding(8.dp),
                )
            }
        }
        val list = devices
        if (list == null) {
            Text(stringResource(R.string.pick_loading), Modifier.padding(24.dp))
            return@Column
        }
        if (list.isEmpty()) {
            EmptyState(Glyph.DEVICES, stringResource(R.string.devices_empty))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PrimaryButton(stringResource(R.string.home_start_scanning), onScan, glyph = Glyph.ROCKET)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val favs = list.filter { it.favorite }
            val recents = list.filter { !it.favorite }
            if (favs.isNotEmpty()) item { SectionTitle(stringResource(R.string.devices_favorites)) }
            items(favs, key = { "f" + it.deviceId }) { d ->
                DeviceRow(d, conn?.peerId == d.deviceId, { io { ShareManager.devices.setFavorite(d.deviceId, false) } }, { io { ShareManager.devices.remove(d.deviceId) } })
            }
            if (recents.isNotEmpty()) item { SectionTitle(stringResource(R.string.devices_recent)) }
            items(recents, key = { "r" + it.deviceId }) { d ->
                DeviceRow(d, conn?.peerId == d.deviceId, { io { ShareManager.devices.setFavorite(d.deviceId, true) } }, { io { ShareManager.devices.remove(d.deviceId) } })
            }
            item {
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.home_start_scanning), onScan, Modifier.fillMaxWidth(), glyph = Glyph.ROCKET)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DeviceRow(d: KnownDevice, connected: Boolean, onFav: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (connected) Palette.Aurora else MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(deviceGlyph(d.icon), Color.White, 22.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.username.ifBlank { d.model }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(d.model, d.nickname).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                Text(
                    stringResource(R.string.devices_last_seen, Fmt.dateTime(ctx, d.lastSeen)) + " · " +
                        stringResource(R.string.devices_transfers, Fmt.number(ctx, d.transfers)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            com.starlane.share.ui.IconButtonG(
                Glyph.HEART, stringResource(if (d.favorite) R.string.devices_unfavorite else R.string.devices_favorite), onFav,
                tint = if (d.favorite) Palette.Danger else MaterialTheme.colorScheme.outline,
            )
            com.starlane.share.ui.IconButtonG(Glyph.CLOSE, stringResource(R.string.action_remove), onRemove, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// USB cable
// ------------------------------------------------------------------------------------------------
@Composable
fun UsbScreen(onBack: () -> Unit, onWireless: () -> Unit) {
    val status by ShareManager.usbStatus.collectAsState()
    val conn by ShareManager.connection.collectAsState()
    LaunchedEffect(Unit) { ShareManager.startUsb() }
    // Capability flags come from PackageManager features; safe to read once the engine is started.
    val caps by produceState<Pair<Boolean, Boolean>?>(null, status) {
        value = withContext(Dispatchers.IO) {
            ShareManager.ensureStarted()
            ShareManager.usb.hostSupported to ShareManager.usb.accessorySupported
        }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(stringResource(R.string.usb_title), onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.usb_intro), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            SectionCard {
                Column {
                    val c = caps
                    if (c != null) {
                        Text(stringResource(R.string.usb_host_support, stringResource(if (c.first) R.string.yes else R.string.no)), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.usb_accessory_support, stringResource(if (c.second) R.string.yes else R.string.no)), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.USB, if (status == UsbTransport.Status.CONNECTED) Palette.Aurora else MaterialTheme.colorScheme.primary, 26.dp)
                        Spacer(Modifier.width(10.dp))
                        val err = if (ShareManager.isStarted) ShareManager.usb.lastError else ""
                        Text(usbStatusText(status, err), style = MaterialTheme.typography.titleMedium)
                    }
                    val cn = conn
                    if (status == UsbTransport.Status.CONNECTED && cn != null) {
                        Text(stringResource(R.string.status_connected_to, cn.peerName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.usb_retry), { ShareManager.startUsb() }, Modifier.weight(1f), glyph = Glyph.RETRY)
                PrimaryButton(stringResource(R.string.usb_use_wireless), onWireless, Modifier.weight(1f), glyph = Glyph.WIFI)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.usb_fallback), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun usbStatusText(s: UsbTransport.Status, err: String): String = when (s) {
    UsbTransport.Status.NO_USB_SUPPORT -> stringResource(R.string.usb_status_NO_USB_SUPPORT)
    UsbTransport.Status.NOTHING_CONNECTED -> stringResource(R.string.usb_status_NOTHING_CONNECTED)
    UsbTransport.Status.DEVICE_ATTACHED -> stringResource(R.string.usb_status_DEVICE_ATTACHED)
    UsbTransport.Status.SWITCHING -> stringResource(R.string.usb_status_SWITCHING)
    UsbTransport.Status.ACCESSORY_ATTACHED -> stringResource(R.string.usb_status_ACCESSORY_ATTACHED)
    UsbTransport.Status.CONNECTED -> stringResource(R.string.usb_status_CONNECTED)
    UsbTransport.Status.PEER_NOT_AOA -> stringResource(R.string.usb_status_PEER_NOT_AOA)
    UsbTransport.Status.PERMISSION_DENIED -> stringResource(R.string.usb_status_PERMISSION_DENIED)
    UsbTransport.Status.FAILED -> stringResource(R.string.usb_status_FAILED, err)
}
