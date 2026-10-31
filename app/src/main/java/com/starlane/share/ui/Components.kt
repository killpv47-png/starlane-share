package com.starlane.share.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icons are drawn with Canvas paths instead of pulling in the large material-icons-extended
 * library (keeps the APK small and avoids loading thousands of vector classes).
 */
enum class Glyph { PHONE, TABLET, LAPTOP, WATCH, STAR, ROCKET, SEND, RECEIVE, HISTORY, DEVICES, USB, SETTINGS, BACK, CLOSE, CHECK, FOLDER, FILE, IMAGE, VIDEO, AUDIO, DOC, APP, DOWNLOAD, SEARCH, PAUSE, PLAY, RETRY, HEART, BOLT, LOCK, WIFI, BLUETOOTH }

@Composable
fun GlyphIcon(g: Glyph, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(g, tint) }
}

fun DrawScope.drawGlyph(g: Glyph, c: Color) {
    val w = size.width
    val h = size.height
    val s = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(c, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
    fun rrect(x: Float, y: Float, ww: Float, hh: Float, r: Float, fill: Boolean = false) =
        drawRoundRect(c, Offset(x * w, y * h), Size(ww * w, hh * h), CornerRadius(r * w), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else s)
    when (g) {
        Glyph.PHONE -> { rrect(0.28f, 0.08f, 0.44f, 0.84f, 0.1f); line(0.44f, 0.8f, 0.56f, 0.8f) }
        Glyph.TABLET -> { rrect(0.14f, 0.12f, 0.72f, 0.76f, 0.08f); line(0.45f, 0.8f, 0.55f, 0.8f) }
        Glyph.LAPTOP -> { rrect(0.18f, 0.2f, 0.64f, 0.44f, 0.05f); line(0.08f, 0.78f, 0.92f, 0.78f) }
        Glyph.WATCH -> { rrect(0.3f, 0.26f, 0.4f, 0.48f, 0.12f); line(0.4f, 0.1f, 0.6f, 0.1f); line(0.4f, 0.9f, 0.6f, 0.9f) }
        Glyph.STAR -> drawPath(star4(w, h), c)
        Glyph.ROCKET -> {
            val p = Path().apply { moveTo(0.5f * w, 0.06f * h); cubicTo(0.75f * w, 0.25f * h, 0.7f * w, 0.55f * h, 0.62f * w, 0.7f * h); lineTo(0.38f * w, 0.7f * h); cubicTo(0.3f * w, 0.55f * h, 0.25f * w, 0.25f * h, 0.5f * w, 0.06f * h); close() }
            drawPath(p, c, style = s); drawCircle(c, 0.07f * w, Offset(0.5f * w, 0.36f * h)); line(0.42f, 0.82f, 0.42f, 0.92f); line(0.58f, 0.82f, 0.58f, 0.92f)
        }
        Glyph.SEND -> { line(0.5f, 0.85f, 0.5f, 0.18f); line(0.25f, 0.42f, 0.5f, 0.15f); line(0.75f, 0.42f, 0.5f, 0.15f) }
        Glyph.RECEIVE -> { line(0.5f, 0.15f, 0.5f, 0.82f); line(0.25f, 0.58f, 0.5f, 0.85f); line(0.75f, 0.58f, 0.5f, 0.85f) }
        Glyph.HISTORY -> { drawCircle(c, 0.38f * w, Offset(0.5f * w, 0.5f * h), style = s); line(0.5f, 0.28f, 0.5f, 0.52f); line(0.5f, 0.52f, 0.66f, 0.62f) }
        Glyph.DEVICES -> { rrect(0.1f, 0.18f, 0.5f, 0.64f, 0.06f); rrect(0.62f, 0.38f, 0.28f, 0.44f, 0.06f) }
        Glyph.USB -> { line(0.5f, 0.1f, 0.5f, 0.9f); line(0.5f, 0.55f, 0.28f, 0.4f); line(0.5f, 0.65f, 0.72f, 0.5f); drawCircle(c, 0.07f * w, Offset(0.5f * w, 0.9f * h)) }
        Glyph.SETTINGS -> { drawCircle(c, 0.16f * w, Offset(0.5f * w, 0.5f * h), style = s); for (i in 0 until 6) { val a = Math.toRadians(i * 60.0); line(0.5f + 0.27f * Math.cos(a).toFloat(), 0.5f + 0.27f * Math.sin(a).toFloat(), 0.5f + 0.4f * Math.cos(a).toFloat(), 0.5f + 0.4f * Math.sin(a).toFloat()) } }
        Glyph.BACK -> { line(0.7f, 0.5f, 0.25f, 0.5f); line(0.45f, 0.28f, 0.23f, 0.5f); line(0.45f, 0.72f, 0.23f, 0.5f) }
        Glyph.CLOSE -> { line(0.25f, 0.25f, 0.75f, 0.75f); line(0.75f, 0.25f, 0.25f, 0.75f) }
        Glyph.CHECK -> { line(0.2f, 0.52f, 0.42f, 0.74f); line(0.42f, 0.74f, 0.8f, 0.3f) }
        Glyph.FOLDER -> { val p = Path().apply { moveTo(0.1f * w, 0.25f * h); lineTo(0.4f * w, 0.25f * h); lineTo(0.48f * w, 0.35f * h); lineTo(0.9f * w, 0.35f * h); lineTo(0.9f * w, 0.8f * h); lineTo(0.1f * w, 0.8f * h); close() }; drawPath(p, c, style = s) }
        Glyph.FILE, Glyph.DOC -> { val p = Path().apply { moveTo(0.25f * w, 0.1f * h); lineTo(0.6f * w, 0.1f * h); lineTo(0.78f * w, 0.28f * h); lineTo(0.78f * w, 0.9f * h); lineTo(0.25f * w, 0.9f * h); close() }; drawPath(p, c, style = s); if (g == Glyph.DOC) { line(0.36f, 0.5f, 0.66f, 0.5f); line(0.36f, 0.66f, 0.66f, 0.66f) } }
        Glyph.IMAGE -> { rrect(0.12f, 0.18f, 0.76f, 0.64f, 0.08f); drawCircle(c, 0.07f * w, Offset(0.34f * w, 0.38f * h)); line(0.2f, 0.74f, 0.45f, 0.52f); line(0.45f, 0.52f, 0.8f, 0.74f) }
        Glyph.VIDEO -> { rrect(0.1f, 0.25f, 0.56f, 0.5f, 0.08f); val p = Path().apply { moveTo(0.7f * w, 0.42f * h); lineTo(0.9f * w, 0.3f * h); lineTo(0.9f * w, 0.7f * h); lineTo(0.7f * w, 0.58f * h); close() }; drawPath(p, c, style = s) }
        Glyph.AUDIO -> { line(0.38f, 0.72f, 0.38f, 0.2f); line(0.38f, 0.2f, 0.78f, 0.12f); line(0.78f, 0.12f, 0.78f, 0.62f); drawCircle(c, 0.1f * w, Offset(0.28f * w, 0.74f * h)); drawCircle(c, 0.1f * w, Offset(0.68f * w, 0.64f * h)) }
        Glyph.APP -> { for (i in 0..1) for (j in 0..1) rrect(0.14f + i * 0.4f, 0.14f + j * 0.4f, 0.3f, 0.3f, 0.08f) }
        Glyph.DOWNLOAD -> { line(0.5f, 0.12f, 0.5f, 0.62f); line(0.3f, 0.44f, 0.5f, 0.64f); line(0.7f, 0.44f, 0.5f, 0.64f); line(0.18f, 0.86f, 0.82f, 0.86f) }
        Glyph.SEARCH -> { drawCircle(c, 0.26f * w, Offset(0.43f * w, 0.43f * h), style = s); line(0.62f, 0.62f, 0.86f, 0.86f) }
        Glyph.PAUSE -> { line(0.36f, 0.22f, 0.36f, 0.78f); line(0.64f, 0.22f, 0.64f, 0.78f) }
        Glyph.PLAY -> { val p = Path().apply { moveTo(0.3f * w, 0.18f * h); lineTo(0.8f * w, 0.5f * h); lineTo(0.3f * w, 0.82f * h); close() }; drawPath(p, c) }
        Glyph.RETRY -> { drawArc(c, 30f, 290f, false, Offset(0.18f * w, 0.18f * h), Size(0.64f * w, 0.64f * h), style = s); line(0.78f, 0.2f, 0.8f, 0.42f); line(0.8f, 0.42f, 0.6f, 0.4f) }
        Glyph.HEART -> { val p = Path().apply { moveTo(0.5f * w, 0.85f * h); cubicTo(0.05f * w, 0.55f * h, 0.15f * w, 0.1f * h, 0.5f * w, 0.32f * h); cubicTo(0.85f * w, 0.1f * h, 0.95f * w, 0.55f * h, 0.5f * w, 0.85f * h); close() }; drawPath(p, c) }
        Glyph.BOLT -> { val p = Path().apply { moveTo(0.58f * w, 0.06f * h); lineTo(0.22f * w, 0.56f * h); lineTo(0.48f * w, 0.56f * h); lineTo(0.42f * w, 0.94f * h); lineTo(0.78f * w, 0.42f * h); lineTo(0.52f * w, 0.42f * h); close() }; drawPath(p, c) }
        Glyph.LOCK -> { rrect(0.22f, 0.45f, 0.56f, 0.42f, 0.08f); drawArc(c, 180f, 180f, false, Offset(0.32f * w, 0.18f * h), Size(0.36f * w, 0.5f * h), style = s) }
        Glyph.WIFI -> { for (i in 1..3) drawArc(c, 225f, 90f, false, Offset((0.5f - i * 0.13f) * w, (0.78f - i * 0.13f) * h), Size(i * 0.26f * w, i * 0.26f * h), style = s); drawCircle(c, 0.05f * w, Offset(0.5f * w, 0.8f * h)) }
        Glyph.BLUETOOTH -> { line(0.32f, 0.3f, 0.66f, 0.66f); line(0.66f, 0.66f, 0.5f, 0.84f); line(0.5f, 0.84f, 0.5f, 0.14f); line(0.5f, 0.14f, 0.66f, 0.32f); line(0.66f, 0.32f, 0.32f, 0.7f) }
    }
}

fun star4(w: Float, h: Float): Path = Path().apply {
    moveTo(0.5f * w, 0.05f * h); lineTo(0.6f * w, 0.4f * h); lineTo(0.95f * w, 0.5f * h); lineTo(0.6f * w, 0.6f * h)
    lineTo(0.5f * w, 0.95f * h); lineTo(0.4f * w, 0.6f * h); lineTo(0.05f * w, 0.5f * h); lineTo(0.4f * w, 0.4f * h); close()
}

val deviceGlyphs = listOf(Glyph.PHONE, Glyph.TABLET, Glyph.WATCH, Glyph.LAPTOP, Glyph.ROCKET, Glyph.STAR)
fun deviceGlyph(icon: Int) = deviceGlyphs[icon.mod(deviceGlyphs.size)]

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) { Box(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(vertical = 8.dp))
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButtonG(Glyph.BACK, androidx.compose.ui.res.stringResource(com.starlane.share.R.string.action_back), onBack, mirror = true)
        } else Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** 48dp touch target with a drawn glyph; [mirror] flips arrows for RTL layouts. */
@Composable
fun IconButtonG(g: Glyph, label: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface, mirror: Boolean = false) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .androidxSemantics(label),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(g, tint, 22.dp, Modifier.then(if (mirror && rtl) Modifier.mirrorX() else Modifier))
    }
}

fun Modifier.mirrorX(): Modifier = this.graphicsLayer(scaleX = -1f)

private fun Modifier.androidxSemantics(label: String): Modifier = this.semantics { contentDescription = label }

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(50))
            .background(if (selected) cs.primary else cs.surfaceVariant)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onPrimary else cs.onSurfaceVariant)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, glyph: Glyph? = null, color: Color = MaterialTheme.colorScheme.primary) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(16.dp))
            .background(if (enabled) color else cs.surfaceVariant)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) { GlyphIcon(glyph, if (enabled) Color.White else cs.onSurfaceVariant, 20.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: Glyph? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, cs.outline, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) { GlyphIcon(glyph, cs.onSurface, 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val wdt = size.width * progress.coerceIn(0f, 1f)
        if (wdt > 0) {
            val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
            drawRoundRect(color, topLeft = Offset(if (rtl) size.width - wdt else 0f, 0f), size = Size(wdt, size.height), cornerRadius = r)
        }
    }
}

@Composable
fun EmptyState(glyph: Glyph, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, MaterialTheme.colorScheme.primary, 32.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(key, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
