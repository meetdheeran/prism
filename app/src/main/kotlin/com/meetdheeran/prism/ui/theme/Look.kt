package com.meetdheeran.prism.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.meetdheeran.prism.R

/** The two complete looks. Switching changes the screens, the assistant, the island and the AOD. */
/** Signal colours offered for the Nothing look. */
val NOTHING_ACCENTS = listOf(0xFFD71921, 0xFFFFFFFF, 0xFFFF6A00, 0xFFFFC700, 0xFF1ED760, 0xFF2E8BFF).map { it.toInt() }

enum class Look(val id: String, val label: String, val note: String) {
    GLASS("glass", "Glass", "Liquid glass, colour glow, Siri-style orb"),
    NOTHING("nothing", "Nothing", "Monochrome, dot-matrix type, one red signal, blunt answers");

    companion object {
        fun from(id: String?): Look = entries.firstOrNull { it.id == id } ?: GLASS
    }
}

/**
 * Process-wide appearance. Snapshot state, so every composition that reads it — the app, the
 * island overlay, the control center, the assistant session, the AOD — recomposes when it changes.
 * [look] is fed from Prefs by PrismApp; [dark] from the system configuration.
 */
object Appearance {
    var look by mutableStateOf(Look.GLASS)
    var dark by mutableStateOf(true)
    /** The Nothing look's one signal colour (red by default). */
    var accent by mutableStateOf(Color(0xFFD71921))
    var dotGrid by mutableStateOf(true)
    /** Dot-matrix type for big titles and clocks; off = grotesk everywhere. */
    var dotTitles by mutableStateOf(true)
    /** 0.3–1.5 multiplier on the glyph edge lights' brightness. */
    var glyphStrength by mutableStateOf(1f)
    var edgeLights by mutableStateOf(true)
    var typewriterCps by mutableStateOf(90)
    /** Island animation speed multiplier (1 = normal, >1 faster). */
    var islandSpeed by mutableStateOf(1f)
    val nothing: Boolean get() = look == Look.NOTHING
    val palette: NothingPalette get() = if (dark) NothingPalette.Dark else NothingPalette.Light
}

/**
 * Nothing-inspired tokens. Black/white canvas, grey cards, hairlines, and signal red as the only
 * colour. No gradients, no blur, no shadows.
 */
data class NothingPalette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    /** Faint dots of the background dot grid. */
    val dot: Color,
) {
    companion object {
        /** The signal colour — red unless the user picked another in Settings. */
        val Red: Color get() = Appearance.accent.let { a ->
            // A white signal disappears on the light theme; it becomes ink there.
            if (a == Color.White && !Appearance.dark) Color.Black else a
        }
        val Dark = NothingPalette(
            bg = Color(0xFF000000), surface = Color(0xFF111111), surface2 = Color(0xFF1C1C1C),
            line = Color(0xFF2A2A2A), text = Color(0xFFFFFFFF), text2 = Color(0xFF8A8A8A),
            text3 = Color(0xFF555555), dot = Color(0xFF1E1E1E),
        )
        val Light = NothingPalette(
            bg = Color(0xFFFFFFFF), surface = Color(0xFFF2F2F2), surface2 = Color(0xFFE6E6E6),
            line = Color(0xFFDDDDDD), text = Color(0xFF000000), text2 = Color(0xFF585A5A),
            text3 = Color(0xFF9A9A9A), dot = Color(0xFFE4E4E4),
        )
    }
}

/**
 * Open-licence (OFL) stand-ins for Nothing's proprietary type: Doto for dot-matrix display text,
 * Space Mono for labels, Space Grotesk for reading. Licence text: docs/fonts-OFL.txt.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object NothingFonts {
    val Dot = FontFamily(
        Font(R.font.doto, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.Setting("ROND", 100f))),
        Font(R.font.doto, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.Setting("ROND", 100f))),
        Font(R.font.doto, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500), FontVariation.Setting("ROND", 100f))),
    )
    val Mono = FontFamily(
        Font(R.font.space_mono, FontWeight.Normal),
        Font(R.font.space_mono_bold, FontWeight.Bold),
    )
    val Grotesk = FontFamily(
        Font(R.font.space_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
}
