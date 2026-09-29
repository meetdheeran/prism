package com.meetdheeran.prism.ui.nothing

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meetdheeran.prism.ui.siri.Phase
import com.meetdheeran.prism.ui.theme.Appearance
import com.meetdheeran.prism.ui.theme.NothingPalette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A round glyph matrix: a 25×25 grid of dots clipped to a circle, like the LED matrix on the back
 * of the Phone (3). It stands in for the Siri orb in the Nothing look.
 *  - Idle: a slow ripple travelling outward.
 *  - Listening: a ring whose radius follows the microphone, with a red centre dot.
 *  - Thinking: a sweep arm rotating around the centre.
 *  - Speaking: the whole disc pulsing with the voice.
 */
@Composable
fun GlyphMatrix(modifier: Modifier = Modifier, level: Float = 0f, phase: Phase = Phase.Idle, size: Dp = 120.dp, grid: Int = 25) {
    val inf = rememberInfiniteTransition(label = "glyph")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(if (phase == Phase.Thinking) 1400 else 2600, easing = LinearEasing)), label = "t")
    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), tween(90), label = "lvl")
    val on = Appearance.palette.text
    val off = Appearance.palette.text.copy(alpha = 0.08f)
    Canvas(modifier.size(size)) {
        val cell = this.size.minDimension / grid
        val r = cell * 0.36f
        val half = (grid - 1) / 2f
        for (gy in 0 until grid) for (gx in 0 until grid) {
            val dx = gx - half
            val dy = gy - half
            val d = hypot(dx, dy) / half
            if (d > 1.02f) continue
            val b = when (phase) {
                Phase.Idle -> ((sin((d * 2.2f - t) * 2 * PI) + 1) / 2).toFloat().let { it * it } * (1f - d * 0.6f)
                Phase.Listening -> {
                    val ring = 0.25f + 0.7f * lvl
                    (1f - abs(d - ring) * 5f).coerceIn(0f, 1f)
                }
                Phase.Thinking -> {
                    val a = ((atan2(dy, dx) / (2 * PI) + 1.0) % 1.0).toFloat()
                    val behind = ((t - a) + 1f) % 1f
                    (1f - behind * 3f).coerceIn(0f, 1f) * (if (d > 0.25f) 1f else 0f)
                }
                Phase.Speaking -> (0.25f + 0.75f * lvl) * (1f - d * 0.5f)
            }
            val c = Offset((gx + 0.5f) * cell, (gy + 0.5f) * cell)
            drawCircle(off, r, c)
            if (b > 0.04f) drawCircle(on.copy(alpha = b.coerceIn(0f, 1f)), r, c)
        }
        if (phase == Phase.Listening) drawCircle(NothingPalette.Red, cell * 0.9f, center)
    }
}

/**
 * Glyph lights for the screen edge — the Nothing answer to the Siri rainbow ring. Short LED strips
 * sit at fixed places around the edge, like the Glyph strips on the back of the Phone (1)/(2).
 * Listening makes them all glow with the voice; thinking makes the light chase round the strips.
 * White LEDs vanish over a white app, so each strip gets a faint dark halo underneath.
 */
@Composable
fun GlyphEdge(active: Boolean, level: Float, phase: Phase, modifier: Modifier = Modifier) {
    val appear by animateFloatAsState(if (active && Appearance.edgeLights) 1f else 0f, tween(260), label = "appear")
    if (appear <= 0.005f) return
    val strength = Appearance.glyphStrength
    val inf = rememberInfiniteTransition(label = "edge")
    val chase by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "chase")
    val breath by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), tween(90), label = "lvl")
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = (appear * strength).coerceAtMost(1f); renderEffect = BlurEffect(22f * strength, 22f * strength, TileMode.Decal) }) {
            glyphStrips(phase, chase, breath, lvl, Color.White, halo = true)
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = (appear * (0.4f + 0.6f * strength)).coerceAtMost(1f) }) {
            glyphStrips(phase, chase, breath, lvl, Color.White, halo = false)
        }
    }
}

private fun DrawScope.glyphStrips(phase: Phase, chase: Float, breath: Float, lvl: Float, color: Color, halo: Boolean) {
    val w = size.width
    val h = size.height
    val th = 5.dp.toPx()
    val inset = 3.dp.toPx()
    // Eight strips clockwise from the top-left: top pair, right pair, bottom pair, left pair.
    val strips = listOf(
        Offset(w * 0.12f, inset) to Size(w * 0.26f, th),
        Offset(w * 0.62f, inset) to Size(w * 0.26f, th),
        Offset(w - inset - th, h * 0.14f) to Size(th, h * 0.22f),
        Offset(w - inset - th, h * 0.60f) to Size(th, h * 0.22f),
        Offset(w * 0.62f, h - inset - th) to Size(w * 0.26f, th),
        Offset(w * 0.12f, h - inset - th) to Size(w * 0.26f, th),
        Offset(inset, h * 0.60f) to Size(th, h * 0.22f),
        Offset(inset, h * 0.14f) to Size(th, h * 0.22f),
    )
    strips.forEachIndexed { i, (o, s) ->
        val a = when (phase) {
            Phase.Thinking -> {
                val pos = i / strips.size.toFloat()
                val behind = ((chase - pos) + 1f) % 1f
                (1f - behind * 2.5f).coerceIn(0.08f, 1f)
            }
            Phase.Listening -> 0.25f + 0.2f * breath + 0.55f * lvl
            Phase.Speaking -> 0.3f + 0.7f * lvl
            Phase.Idle -> 0.2f
        }
        if (halo) {
            drawRoundRect(Color.Black.copy(alpha = 0.35f * a), o - Offset(th, th), Size(s.width + th * 2, s.height + th * 2), CornerRadius(th * 2))
            drawRoundRect(color.copy(alpha = a), o, s, CornerRadius(th))
        } else {
            drawRoundRect(color.copy(alpha = a), o, s, CornerRadius(th / 2))
        }
    }
}

/** A 3×3 dot block where one dot at a time lights and walks the grid. Nothing's "working on it". */
@Composable
fun DotLoader(modifier: Modifier = Modifier, color: Color = Appearance.palette.text, dot: Dp = 4.dp) {
    val inf = rememberInfiniteTransition(label = "loader")
    val t by inf.animateFloat(0f, 8f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "t")
    // Spiral order round the outside of the block, so the light looks like it is circling.
    val order = intArrayOf(0, 1, 2, 5, 8, 7, 6, 3)
    Canvas(modifier.size(dot * 5)) {
        val cell = size.width / 3
        for (i in 0 until 9) {
            val c = Offset((i % 3 + 0.5f) * cell, (i / 3 + 0.5f) * cell)
            val idx = order.indexOf(i)
            val lit = if (idx < 0) 0.15f else {
                val behind = ((t - idx) + 8f) % 8f
                (1f - behind / 3f).coerceIn(0.15f, 1f)
            }
            drawCircle(color.copy(alpha = lit), dot.toPx() / 2, c)
        }
    }
}

/**
 * Types [text] out a few characters at a time with a solid block cursor, like a dot-matrix readout.
 * Already-shown text is kept when [text] grows (streaming), so only the new part types out.
 */
@Composable
fun Typewriter(text: String, style: TextStyle, modifier: Modifier = Modifier, cps: Int = Appearance.typewriterCps, onDone: () -> Unit = {}) {
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        if (shown > text.length) shown = 0
        while (shown < text.length) {
            shown = (shown + 2).coerceAtMost(text.length)
            delay((2000L / cps.coerceAtLeast(1)).coerceAtLeast(8L))
        }
        onDone()
    }
    val done = shown >= text.length
    val cursor = Appearance.palette.text
    BasicText(
        buildAnnotatedString {
            append(text.take(shown))
            if (!done) withStyle(SpanStyle(background = cursor, color = cursor)) { append(" ") }
        },
        modifier,
        style = style,
    )
}
