package com.meetdheeran.prism.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Palette. In the glass look everything is fixed (glass over OLED black is the look) and the accent
 * is user-changeable. In the Nothing look the palette follows the phone's dark mode and the only
 * accent is signal red. Colours are getters over [Appearance], so switching looks recomposes every
 * window — the app, the island and the control-center overlays — without passing anything down.
 */
object PrismColors {
    private val g get() = !Appearance.nothing
    private val n get() = Appearance.palette

    val Ink get() = if (g) Color(0xFF05070D) else n.bg
    val Navy get() = if (g) Color(0xFF0B1020) else n.surface
    val Slate get() = if (g) Color(0xFF151B2C) else n.surface2
    val TextPrimary get() = if (g) Color(0xFFF5F7FA) else n.text
    val TextSecondary get() = if (g) Color(0xB3F5F7FA) else n.text2
    val TextTertiary get() = if (g) Color(0x73F5F7FA) else n.text3
    val DefaultAccent = Color(0xFF0A84FF)

    // Siri-style gradient stops (iOS 18 edge glow). Order matters for the sweep. Glass look only.
    val SiriPink = Color(0xFFFF2D87)
    val SiriOrange get() = if (g) Color(0xFFFF8A3D) else NothingPalette.Red
    val SiriPurple = Color(0xFF8A5CFF)
    val SiriBlue = Color(0xFF3B7BFF)
    val SiriCyan get() = if (g) Color(0xFF3CC8FF) else n.text
    val SiriStops = listOf(SiriBlue, Color(0xFF3CC8FF), SiriPink, Color(0xFFFF8A3D), SiriPurple, SiriBlue)

    val Good get() = if (g) Color(0xFF30D158) else n.text
    val Warn get() = if (g) Color(0xFFFFD60A) else NothingPalette.Red
    val Bad get() = if (g) Color(0xFFFF453A) else NothingPalette.Red
}

val LocalAccent = compositionLocalOf { PrismColors.DefaultAccent }

private fun style(size: Int, weight: FontWeight, tracking: Float = 0f, line: Int = (size * 1.25f).toInt(), family: FontFamily? = null) =
    TextStyle(fontSize = size.sp, fontWeight = weight, letterSpacing = tracking.sp, lineHeight = line.sp, color = PrismColors.TextPrimary, fontFamily = family)

/** Tight, SF-Pro-like tracking on the system font. Large titles pull in, captions open up. */
private fun glassTypography() = Typography(
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

/**
 * Nothing look: dot-matrix for display sizes only (it is unreadable small), a grotesk for reading,
 * and mono for the small spec-sheet labels.
 */
private fun nothingTypography(dot: Boolean) = Typography(
    displayLarge = if (dot) style(52, FontWeight.Bold, 0f, 56, NothingFonts.Dot) else style(44, FontWeight.Medium, -1.2f, family = NothingFonts.Grotesk),
    displayMedium = if (dot) style(38, FontWeight.Bold, 0f, 42, NothingFonts.Dot) else style(34, FontWeight.Medium, -0.8f, family = NothingFonts.Grotesk),
    headlineLarge = if (dot) style(30, FontWeight.Bold, 0f, 34, NothingFonts.Dot) else style(28, FontWeight.Medium, -0.5f, family = NothingFonts.Grotesk),
    headlineMedium = style(22, FontWeight.Medium, -0.3f, family = NothingFonts.Grotesk),
    titleLarge = style(20, FontWeight.Medium, -0.2f, family = NothingFonts.Grotesk),
    titleMedium = style(17, FontWeight.Medium, -0.1f, family = NothingFonts.Grotesk),
    titleSmall = style(15, FontWeight.Medium, family = NothingFonts.Grotesk),
    bodyLarge = style(17, FontWeight.Normal, family = NothingFonts.Grotesk),
    bodyMedium = style(15, FontWeight.Normal, family = NothingFonts.Grotesk),
    bodySmall = style(13, FontWeight.Normal, family = NothingFonts.Grotesk),
    labelLarge = style(14, FontWeight.Bold, 0.6f, family = NothingFonts.Mono),
    labelMedium = style(12, FontWeight.Normal, 0.6f, family = NothingFonts.Mono),
    labelSmall = style(11, FontWeight.Normal, 0.8f, family = NothingFonts.Mono),
)

private val typographyCache = HashMap<Triple<Look, Boolean, Boolean>, Typography>()

/** Text colours are baked into the styles, so there is one instance per look + light/dark. */
val PrismTypography: Typography
    get() {
        val key = Triple(Appearance.look, Appearance.dark, Appearance.dotTitles)
        return typographyCache.getOrPut(key) { if (key.first == Look.NOTHING) nothingTypography(key.third) else glassTypography() }
    }

@Composable
fun PrismTheme(accent: Color = PrismColors.DefaultAccent, content: @Composable () -> Unit) {
    val nothing = Appearance.nothing
    val effectiveAccent = if (nothing) NothingPalette.Red else accent
    val scheme = if (nothing) {
        val p = Appearance.palette
        val base = if (Appearance.dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = p.text, onPrimary = p.bg, secondary = NothingPalette.Red,
            background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surface2, onSurfaceVariant = p.text2, outline = p.line, error = NothingPalette.Red,
            surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface2,
        )
    } else darkColorScheme(
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
    CompositionLocalProvider(LocalAccent provides effectiveAccent) {
        MaterialTheme(colorScheme = scheme, typography = PrismTypography, content = content)
    }
}
