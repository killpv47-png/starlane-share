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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starlane.core.engine.TransferState
import com.starlane.core.engine.TurboStatus
import com.starlane.share.R
import com.starlane.share.engine.ShareManager
import com.starlane.share.engine.TransferUi
import com.starlane.share.files.Selection
import com.starlane.share.ui.Fmt
import com.starlane.share.ui.Glyph
import com.starlane.share.ui.GlyphIcon
import com.starlane.share.ui.KeyValue
import com.starlane.share.ui.Palette
import com.starlane.share.ui.ProgressBar
import com.starlane.share.ui.SecondaryButton
import com.starlane.share.ui.deviceGlyph

/** Routes reachable from the home screen. */
enum class Route { HOME, DISCOVERY, PICKER, TRANSFERS, HISTORY, DEVICES, USB, SETTINGS, APP_DETAIL }

@Composable
fun HomeScreen(navigate: (Route) -> Unit) {
    val ctx = LocalContext.current
    val prefs = ShareManager.prefs
    val conn by ShareManager.connection.collectAsState()
    val waiting by ShareManager.receiveWaiting.collectAsState()
    var turbo by remember { mutableStateOf(prefs.turbo) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Palette.Orbit, Palette.Aurora))), contentAlignment = Alignment.Center) {
                GlyphIcon(deviceGlyph(prefs.icon), Color.White, 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_hello, prefs.username), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ShareManager.deviceModel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            com.starlane.share.ui.IconButtonG(Glyph.SETTINGS, stringResource(R.string.home_settings), { navigate(Route.SETTINGS) })
        }
        Spacer(Modifier.height(16.dp))

        StatusPanel(onOpenTransfers = { navigate(Route.TRANSFERS) })
        Spacer(Modifier.height(16.dp))

        // Primary action: the space-style discovery screen.
        Box(
            Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Palette.Night, Color(0xFF1A2560), Palette.OrbitDeep)))
                .clickable(role = Role.Button) { navigate(Route.DISCOVERY) }
                .padding(20.dp),
        ) {
            Column(Modifier.align(Alignment.CenterStart)) {
                Text(stringResource(R.string.home_start_scanning), color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.home_scan_hint), color = Palette.Mist, style = MaterialTheme.typography.bodyMedium)
            }
            GlyphIcon(Glyph.ROCKET, Color.White.copy(alpha = 0.9f), 48.dp, Modifier.align(Alignment.CenterEnd))
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(
                Glyph.RECEIVE, stringResource(R.string.home_receive), stringResource(R.string.home_receive_hint), Modifier.weight(1f),
                active = waiting,
            ) { if (waiting) ShareManager.stopReceiveMode() else ShareManager.startReceiveMode() }
            Tile(Glyph.SEND, stringResource(R.string.home_send_files), stringResource(R.string.home_send_hint), Modifier.weight(1f)) {
                navigate(Route.PICKER)
            }
        }
        if (waiting) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.home_waiting, prefs.username), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                SecondaryButton(stringResource(R.string.home_stop_waiting), { ShareManager.stopReceiveMode() })
            }
        }
        if (!Selection.isEmpty && conn == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.home_shared_pending, pluralStringResource(R.plurals.files_count, Selection.count, Selection.count)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))

        // Turbo Mode: a real setting used by the tuner for every new transfer.
        com.starlane.share.ui.SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.BOLT, Palette.Star, 26.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.turbo_mode), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.turbo_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ShareManager.isStarted && ShareManager.deviceProfile.lowEnd) {
                        Text(stringResource(R.string.turbo_note_LOCAL_LOW_END), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Switch(checked = turbo, onCheckedChange = { turbo = it; prefs.turbo = it })
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallTile(Glyph.HISTORY, stringResource(R.string.home_history), Modifier.weight(1f)) { navigate(Route.HISTORY) }
            SmallTile(Glyph.DEVICES, stringResource(R.string.home_devices), Modifier.weight(1f)) { navigate(Route.DEVICES) }
            SmallTile(Glyph.USB, stringResource(R.string.home_usb), Modifier.weight(1f)) { navigate(Route.USB) }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.LOCK, MaterialTheme.colorScheme.onSurfaceVariant, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.onb_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
    @Suppress("UNUSED_VARIABLE") val unused = ctx
}

@Composable
private fun Tile(g: Glyph, title: String, hint: String, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(22.dp))
            .background(if (active) cs.secondary.copy(alpha = 0.18f) else cs.surface)
            .clickable(role = Role.Button, onClick = onClick).padding(16.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            GlyphIcon(g, cs.primary, 22.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun SmallTile(g: Glyph, title: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(cs.surface).clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphIcon(g, cs.primary, 22.dp)
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Always-visible connection & transfer status. Every number is measured: speed from real bytes
 * over real time, signal only when the Wi-Fi driver reports it (otherwise "not reported").
 */
@Composable
fun StatusPanel(onOpenTransfers: () -> Unit) {
    val ctx = LocalContext.current
    val conn by ShareManager.connection.collectAsState()
    val connecting by ShareManager.connecting.collectAsState()
    val transfers by ShareManager.transfers.collectAsState()
    val active: TransferUi? = transfers.firstOrNull { !it.state.terminal } ?: transfers.firstOrNull()
    val cs = MaterialTheme.colorScheme

    com.starlane.share.ui.SectionCard(Modifier.clickable(role = Role.Button, onClick = onOpenTransfers)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when { conn != null -> Palette.Aurora; connecting != null -> Palette.Star; else -> cs.outline }
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        conn != null -> stringResource(R.string.status_connected_to, conn!!.peerName)
                        connecting != null -> stringResource(R.string.status_connecting, connecting!!.device.displayName)
                        else -> stringResource(R.string.status_not_connected)
                    },
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (conn != null) {
                    Text(stringResource(R.string.action_disconnect), color = cs.error, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { ShareManager.disconnect() }.padding(8.dp))
                }
            }
            conn?.let { c ->
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KeyValue(stringResource(R.string.status_method), methodLabel(c.method.name), Modifier.weight(1f))
                    val q = when {
                        c.rssiDbm != null && c.linkMbps != null -> stringResource(R.string.status_signal, c.rssiDbm, c.linkMbps)
                        c.rttMs >= 0 -> stringResource(R.string.status_rtt, Fmt.number(ctx, c.rttMs))
                        else -> stringResource(R.string.status_quality_na)
                    }
                    KeyValue(stringResource(R.string.status_quality), q, Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.LOCK, cs.secondary, 12.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.status_encrypted, c.cipher.replace('_', '-')), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (c.lanes > 1) {
                        Text(" · " + stringResource(R.string.status_lanes, c.lanes), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (active == null) {
                Text(stringResource(R.string.status_idle), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (active.direction == com.starlane.core.engine.Direction.SEND) R.string.transfer_sending else R.string.transfer_receiving, active.peerName),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    TurboBadge(active.turbo)
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.percent(ctx, active.progress), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                ProgressBar(active.progress, color = if (active.state == TransferState.COMPLETED) Palette.Aurora else cs.primary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyValue(stringResource(R.string.status_speed), Fmt.speed(ctx, active.speed), Modifier.weight(1f))
                    KeyValue(stringResource(R.string.status_avg), Fmt.speed(ctx, active.avgSpeed), Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyValue(stringResource(R.string.status_transferred), "${Fmt.bytes(ctx, active.doneBytes)} / ${Fmt.bytes(ctx, active.totalBytes)}", Modifier.weight(1f))
                    KeyValue(stringResource(R.string.status_remaining), Fmt.duration(ctx, active.etaSec), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun TurboBadge(s: TurboStatus) {
    if (s == TurboStatus.OFF) return
    val (label, color) = when (s) {
        TurboStatus.ACTIVE -> stringResource(R.string.turbo_active) to Palette.Star
        TurboStatus.LIMITED -> stringResource(R.string.turbo_limited) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(R.string.turbo_unavailable) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.BOLT, color, 12.dp)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
