package com.meetdheeran.prism.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Palette. The accent is user-changeable (Settings.accentArgb); everything else is fixed
 * so glass reads the same on every screen. Dark-only on purpose: glass over OLED black is the look.
 */
object PrismColors {
    val Ink = Color(0xFF05070D)
    val Navy = Color(0xFF0B1020)
    val Slate = Color(0xFF151B2C)
    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xB3F5F7FA)
    val TextTertiary = Color(0x73F5F7FA)
    val DefaultAccent = Color(0xFF0A84FF)

    // Siri-style gradient stops (iOS 18 edge glow). Order matters for the sweep.
    val SiriPink = Color(0xFFFF2D87)
    val SiriOrange = Color(0xFFFF8A3D)
    val SiriPurple = Color(0xFF8A5CFF)
    val SiriBlue = Color(0xFF3B7BFF)
    val SiriCyan = Color(0xFF3CC8FF)
    val SiriStops = listOf(SiriBlue, SiriCyan, SiriPink, SiriOrange, SiriPurple, SiriBlue)

    val Good = Color(0xFF30D158)
    val Warn = Color(0xFFFFD60A)
    val Bad = Color(0xFFFF453A)
}

val LocalAccent = compositionLocalOf { PrismColors.DefaultAccent }

private fun style(size: Int, weight: FontWeight, tracking: Float = 0f, line: Int = (size * 1.25f).toInt()) =
    TextStyle(fontSize = size.sp, fontWeight = weight, letterSpacing = tracking.sp, lineHeight = line.sp, color = PrismColors.TextPrimary)

/** Tight, SF-Pro-like tracking on the system font. Large titles pull in, captions open up. */
val PrismTypography = Typography(
    displayLarge = style(44, FontWeight.Bold, -1.2f),
    displayMedium = style(34, FontWeight.Bold, -0.8f),
    headlineLarge = style(28, FontWeight.SemiBold, -0.5f),
    headlineMedium = style(22, FontWeight.SemiBold, -0.3f),
    titleLarge = style(20, FontWeight.SemiBold, -0.2f),
    titleMedium = style(17, FontWeight.SemiBold, -0.1f),
    titleSmall = style(15, FontWeight.Medium),
    bodyLarge = style(17, FontWeight.Normal),
    bodyMedium = style(15, FontWeight.Normal),
    bodySmall = style(13, FontWeight.Normal),
    labelLarge = style(15, FontWeight.Medium),
    labelMedium = style(13, FontWeight.Medium, 0.1f),
    labelSmall = style(11, FontWeight.Medium, 0.3f),
)

@Composable
fun PrismTheme(accent: Color = PrismColors.DefaultAccent, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        secondary = PrismColors.SiriCyan,
        background = PrismColors.Ink,
        onBackground = PrismColors.TextPrimary,
        surface = PrismColors.Navy,
        onSurface = PrismColors.TextPrimary,
        surfaceVariant = PrismColors.Slate,
        onSurfaceVariant = PrismColors.TextSecondary,
        outline = Color.White.copy(alpha = 0.18f),
        error = PrismColors.Bad,
    )
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, typography = PrismTypography, content = content)
    }
}
