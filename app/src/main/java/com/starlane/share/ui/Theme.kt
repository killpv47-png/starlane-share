package com.starlane.share.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Starlane palette: deep navy night sky, periwinkle "orbit" accent, aurora teal, warm star amber. */
object Palette {
    val Night = Color(0xFF070B1A)
    val NightRaised = Color(0xFF0F1530)
    val NightCard = Color(0xFF141B3A)
    val Orbit = Color(0xFF6C8CFF)
    val OrbitDeep = Color(0xFF3F5BD8)
    val Aurora = Color(0xFF3EE6C8)
    val Star = Color(0xFFFFB86B)
    val Danger = Color(0xFFFF6B7A)
    val Mist = Color(0xFFB8C1E6)
    val Paper = Color(0xFFF6F7FC)
}

private val Dark = darkColorScheme(
    primary = Palette.Orbit, onPrimary = Color.White, primaryContainer = Palette.OrbitDeep, onPrimaryContainer = Color.White,
    secondary = Palette.Aurora, onSecondary = Color(0xFF00201A), tertiary = Palette.Star, onTertiary = Color(0xFF2B1600),
    background = Palette.Night, onBackground = Color(0xFFE7EAF8), surface = Palette.NightRaised, onSurface = Color(0xFFE7EAF8),
    surfaceVariant = Palette.NightCard, onSurfaceVariant = Palette.Mist, outline = Color(0xFF39426B),
    error = Palette.Danger, onError = Color.White,
)

private val Light = lightColorScheme(
    primary = Palette.OrbitDeep, onPrimary = Color.White, primaryContainer = Color(0xFFDDE3FF), onPrimaryContainer = Color(0xFF0B1A5C),
    secondary = Color(0xFF00897B), onSecondary = Color.White, tertiary = Color(0xFFB86500), onTertiary = Color.White,
    background = Palette.Paper, onBackground = Color(0xFF12152A), surface = Color.White, onSurface = Color(0xFF12152A),
    surfaceVariant = Color(0xFFE9ECF8), onSurfaceVariant = Color(0xFF4A5170), outline = Color(0xFFC3C8DE),
    error = Color(0xFFC62839), onError = Color.White,
)

private val Type = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
)

private val Shape = Shapes(
    small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
)

/** Whether decorative animation should be reduced (setting, or low-end hardware). */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun StarlaneTheme(themeMode: Int, content: @Composable () -> Unit) {
    val dark = when (themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val scheme: ColorScheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = Type, shapes = Shape, content = content)
}
