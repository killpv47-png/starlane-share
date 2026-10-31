package com.starlane.share.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starlane.share.R
import com.starlane.share.engine.PeerAction
import com.starlane.share.engine.ShareManager
import com.starlane.share.net.NearbyDevice
import com.starlane.share.ui.Glyph
import com.starlane.share.ui.GlyphIcon
import com.starlane.share.ui.LocalReduceMotion
import com.starlane.share.ui.Palette
import com.starlane.share.ui.deviceGlyph
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The space-styled discovery screen. Everything shown comes from real discovery results in
 * [ShareManager.nearby]; when nothing is found, an honest empty state is shown.
 *
 * Rendering cost is kept low: the starfield is a single Canvas drawing a fixed number of points
 * (60, or 24 with reduced motion) driven by one infinite transition; device bubbles are plain
 * composables. No particles, shaders or 3D.
 */
@Composable
fun DiscoveryScreen(onBack: () -> Unit) {
    val nearby by ShareManager.nearby.collectAsState()
    val connecting by ShareManager.connecting.collectAsState()
    val reduce = LocalReduceMotion.current
    var selected by remember { mutableStateOf<NearbyDevice?>(null) }
    val view = LocalView.current

    LaunchedEffect(Unit) { ShareManager.startScanning() }
    // Keep the selection in sync if the device disappears.
    LaunchedEffect(nearby) { selected = selected?.let { s -> nearby.firstOrNull { it.deviceId == s.deviceId } } }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF04060F), Palette.Night, Color(0xFF0B1238))))) {
        Starfield(if (reduce) 24 else 60, animate = !reduce)

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Header
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                com.starlane.share.ui.IconButtonG(Glyph.BACK, stringResource(R.string.action_back), {
                    ShareManager.stopScanning(); onBack()
                }, tint = Color.White, mirror = true)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.disc_title), color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                    Text(
                        if (nearby.isEmpty()) stringResource(R.string.disc_scanning) else pluralStringResource(R.plurals.devices_found, nearby.size, nearby.size),
                        color = Palette.Mist, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Orbit field
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val density = LocalDensity.current
                val wPx = with(density) { maxWidth.toPx() }
                val hPx = with(density) { maxHeight.toPx() }
                val center = Offset(wPx / 2, hPx * 0.72f)
                val radius = minOf(wPx, hPx) * 0.38f
                val bubble = 76.dp
                val bubblePx = with(density) { bubble.toPx() }

                OrbitRings(center, radius, animate = !reduce)

                // Self, at the bottom of the orbit field.
                SelfBubble(Modifier.offset { IntOffset((center.x - bubblePx * 0.6f).roundToInt(), (center.y - bubblePx * 0.6f).roundToInt()) })

                // Nearby devices placed on an arc above the user's own device.
                val drag = remember { mutableStateMapOf<String, Offset>() }
                nearby.forEachIndexed { i, d ->
                    val n = nearby.size
                    val angle = if (n == 1) -90.0 else -160.0 + 140.0 * i / (n - 1).coerceAtLeast(1)
                    val ring = if (n > 6 && i % 2 == 1) radius * 1.32f else radius
                    val base = Offset(center.x + (ring * cos(Math.toRadians(angle))).toFloat(), center.y + (ring * sin(Math.toRadians(angle))).toFloat())
                    val dOff = drag[d.deviceId] ?: Offset.Zero
                    DeviceBubble(
                        device = d,
                        selected = selected?.deviceId == d.deviceId,
                        modifier = Modifier
                            .offset { IntOffset((base.x + dOff.x - bubblePx / 2).roundToInt(), (base.y + dOff.y - bubblePx / 2).roundToInt()) }
                            .pointerInput(d.deviceId) {
                                detectDragGestures(
                                    onDragEnd = {
                                        val end = base + (drag[d.deviceId] ?: Offset.Zero)
                                        // Dropped onto (near) our own device -> select it.
                                        if (hypot(end.x - center.x, end.y - center.y) < bubblePx * 1.1f) {
                                            selected = d
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM.takeIf { android.os.Build.VERSION.SDK_INT >= 30 } ?: HapticFeedbackConstants.LONG_PRESS)
                                        }
                                        drag.remove(d.deviceId)
                                    },
                                    onDragCancel = { drag.remove(d.deviceId) },
                                ) { change, amount ->
                                    change.consume()
                                    drag[d.deviceId] = (drag[d.deviceId] ?: Offset.Zero) + amount
                                }
                            },
                        onTap = {
                            selected = d
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        },
                    )
                }

                if (nearby.isEmpty()) {
                    Text(
                        stringResource(R.string.disc_none), color = Palette.Mist, textAlign = TextAlign.Center,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 32.dp, end = 32.dp),
                    )
                } else if (selected == null) {
                    Text(
                        stringResource(R.string.disc_drag_hint), color = Palette.Mist.copy(alpha = 0.8f),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    )
                }
            }

            // Action sheet
            AnimatedVisibility(
                visible = selected != null || connecting != null,
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            ) {
                val dev = connecting?.device ?: selected
                if (dev != null) ActionSheet(dev, connecting != null, connecting?.method?.name) { action ->
                    ShareManager.connect(dev, action)
                }
            }

            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).border(1.dp, Palette.Mist.copy(alpha = 0.4f), RoundedCornerShape(50))
                        .clickable(role = Role.Button) { ShareManager.stopScanning(); onBack() }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) { Text(stringResource(R.string.disc_stop), color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/** Slowly drifting stars. One canvas, one animation clock, fixed star count. */
@Composable
private fun Starfield(count: Int, animate: Boolean) {
    val stars = remember(count) {
        val r = Random(42)
        List(count) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } // x, y, depth
    }
    val t = if (animate) {
        val tr = rememberInfiniteTransition(label = "stars")
        tr.animateFloat(0f, 1f, infiniteRepeatable(tween(90_000, easing = LinearEasing)), label = "drift").value
    } else 0f
    val twinkle = if (animate) {
        val tr = rememberInfiniteTransition(label = "twinkle")
        tr.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "tw").value
    } else 0.85f
    Canvas(Modifier.fillMaxSize()) {
        stars.forEachIndexed { i, (x, y, depth) ->
            // Deeper stars move slower: cheap parallax.
            val yy = ((y + t * (0.15f + depth * 0.35f)) % 1f) * size.height
            val xx = x * size.width
            val alpha = (0.25f + depth * 0.6f) * (if (i % 3 == 0) twinkle else 1f)
            drawCircle(Color.White.copy(alpha = alpha.coerceIn(0f, 1f)), radius = 0.6f + depth * 1.6f, center = Offset(xx, yy))
        }
    }
}

@Composable
private fun OrbitRings(center: Offset, radius: Float, animate: Boolean) {
    val pulse = if (animate) {
        val tr = rememberInfiniteTransition(label = "pulse")
        tr.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "p").value
    } else 0f
    Canvas(Modifier.fillMaxSize()) {
        val stroke = Stroke(width = 1.2f)
        drawCircle(Palette.Orbit.copy(alpha = 0.22f), radius, center, style = stroke)
        drawCircle(Palette.Orbit.copy(alpha = 0.12f), radius * 1.32f, center, style = stroke)
        drawCircle(Palette.Orbit.copy(alpha = 0.08f), radius * 0.6f, center, style = stroke)
        if (animate) drawCircle(Palette.Aurora.copy(alpha = 0.35f * (1f - pulse)), radius * 0.35f + radius * pulse, center, style = Stroke(width = 2f))
    }
}

@Composable
private fun SelfBubble(modifier: Modifier) {
    val prefs = ShareManager.prefs
    Column(modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(92.dp * 0.83f).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Palette.Orbit, Palette.OrbitDeep)))
                .border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(deviceGlyph(prefs.icon), Color.White, 34.dp) }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.disc_you), color = Palette.Aurora, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(prefs.username, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeviceBubble(device: NearbyDevice, selected: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val ring = if (selected) Palette.Aurora else Color.White.copy(alpha = 0.35f)
    val desc = device.displayName + ", " + device.model
    Column(
        modifier.widthIn(max = 110.dp).semantics { contentDescription = desc }
            .clickable(role = Role.Button, onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            device.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Palette.NightCard, Color(0xFF1E2858))))
                .border(if (selected) 2.5.dp else 1.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(deviceGlyph(device.icon), Color.White, 28.dp) }
        Spacer(Modifier.height(4.dp))
        Text(device.model, color = Palette.Mist, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (device.nickname.isNotBlank() && device.nickname != device.displayName) {
            Text(device.nickname, color = Palette.Mist.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            device.via.forEach { k ->
                val g = when (k) { com.starlane.core.link.LinkKind.BLUETOOTH -> Glyph.BLUETOOTH; else -> Glyph.WIFI }
                GlyphIcon(g, Palette.Aurora.copy(alpha = 0.8f), 12.dp)
            }
        }
    }
}

@Composable
private fun ActionSheet(device: NearbyDevice, busy: Boolean, method: String?, onAction: (PeerAction) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp)).background(Palette.NightRaised.copy(alpha = 0.95f))
            .border(1.dp, Palette.Orbit.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.disc_selected, device.displayName), color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text(device.model, color = Palette.Mist, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        if (busy) {
            val label = method?.let { methodLabel(it) } ?: ""
            Text(stringResource(R.string.connect_trying, label), color = Palette.Aurora, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Aurora, trackColor = Palette.NightCard)
        } else {
            Text(stringResource(R.string.disc_choose_action), color = Palette.Mist, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.starlane.share.ui.PrimaryButton(stringResource(R.string.action_send), { onAction(PeerAction.SEND) }, Modifier.weight(1f), glyph = Glyph.SEND)
                com.starlane.share.ui.PrimaryButton(stringResource(R.string.action_receive), { onAction(PeerAction.RECEIVE) }, Modifier.weight(1f), glyph = Glyph.RECEIVE, color = Color(0xFF13997F))
            }
        }
    }
}

@Composable
fun methodLabel(kind: String): String {
    val id = when (kind) {
        "LAN" -> R.string.method_LAN
        "WIFI_DIRECT" -> R.string.method_WIFI_DIRECT
        "HOTSPOT" -> R.string.method_HOTSPOT
        "BLUETOOTH" -> R.string.method_BLUETOOTH
        "USB" -> R.string.method_USB
        else -> R.string.method_LOOPBACK
    }
    return stringResource(id)
}
