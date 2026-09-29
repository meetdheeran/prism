package com.meetdheeran.prism.ui.siri

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meetdheeran.prism.ui.motion.Motion
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.Appearance
import com.meetdheeran.prism.ui.nothing.DotLoader
import com.meetdheeran.prism.ui.nothing.GlyphEdge
import com.meetdheeran.prism.ui.nothing.GlyphMatrix
import kotlin.math.cos
import kotlin.math.sin

enum class Phase { Idle, Listening, Thinking, Speaking }

/**
 * The screen-edge glow of the new Siri: a rainbow ribbon hugging the display edge, blurred
 * outward, breathing with the microphone level. Drawn twice — a blurred body on its own layer
 * and a crisp hairline on top — because blur alone reads as a smudge.
 */
@Composable
fun EdgeGlow(
    active: Boolean,
    level: Float,
    phase: Phase,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 42.dp,
) {
    if (Appearance.nothing) { GlyphEdge(active, level, phase, modifier); return }
    if (!Appearance.edgeLights) return
    val appear by animateFloatAsState(if (active) 1f else 0f, Motion.panel(), label = "appear")
    if (appear <= 0.005f) return
    val inf = rememberInfiniteTransition(label = "glow")
    val angle by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (phase == Phase.Thinking) 2400 else 6000, easing = LinearEasing)), label = "angle",
    )
    val breath by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
    )
    val target = when (phase) {
        Phase.Listening -> 0.10f + 0.14f * breath + 0.80f * level.coerceIn(0f, 1f)
        Phase.Thinking -> 0.35f + 0.3f * breath
        Phase.Speaking -> 0.25f + 0.5f * level.coerceIn(0f, 1f)
        Phase.Idle -> 0.12f
    }
    val lvl by animateFloatAsState(target, Motion.gentle(), label = "level")
    val density = LocalDensity.current
    val blurPx = with(density) { (18.dp + 34.dp * lvl).toPx() }
    val strokePx = with(density) { (7.dp + 24.dp * lvl).toPx() }
    val cornerPx = with(density) { cornerRadius.toPx() }

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    renderEffect = BlurEffect(blurPx, blurPx, TileMode.Decal)
                    alpha = appear
                    scaleX = 0.97f + 0.03f * appear
                    scaleY = 0.97f + 0.03f * appear
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
        ) { drawRibbon(angle, strokePx, cornerPx, 0.95f) }
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = appear * 0.85f }) {
            drawRibbon(angle + 0.12f, strokePx * 0.16f, cornerPx, 0.9f)
        }
    }
}

private fun DrawScope.drawRibbon(phase: Float, stroke: Float, corner: Float, alpha: Float) {
    val stops = rotatedStops(PrismColors.SiriStops, phase, alpha)
    val brush = Brush.sweepGradient(*stops, center = center)
    drawRoundRect(
        brush,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(stroke),
    )
}

/** A sweep gradient has no angle parameter, so we rotate the stop offsets instead. */
private fun rotatedStops(colors: List<Color>, t: Float, alpha: Float): Array<Pair<Float, Color>> {
    val n = colors.size - 1
    val raw = (0 until n).map { i -> (((i.toFloat() / n) + t) % 1f) to colors[i].copy(alpha = alpha) }.sortedBy { it.first }
    val first = raw.first()
    val last = raw.last()
    val span = first.first + (1f - last.first)
    val f = if (span > 0f) (1f - last.first) / span else 0f
    val c0 = lerp(last.second, first.second, f)
    return (listOf(0f to c0) + raw + listOf(1f to c0)).toTypedArray()
}

/** The Siri sphere: additive colour blobs drifting inside a glass ball, swelling with the voice. */
@Composable
fun Orb(modifier: Modifier = Modifier, level: Float = 0f, phase: Phase = Phase.Idle, size: Dp = 120.dp) {
    if (Appearance.nothing) { GlyphMatrix(modifier, level, phase, size); return }
    val inf = rememberInfiniteTransition(label = "orb")
    val speed = if (phase == Phase.Thinking) 0.5f else 1f
    val t1 by inf.animateFloat(0f, 360f, infiniteRepeatable(tween((6000 * speed).toInt(), easing = LinearEasing)), label = "t1")
    val t2 by inf.animateFloat(0f, 360f, infiniteRepeatable(tween((9500 * speed).toInt(), easing = LinearEasing)), label = "t2")
    val t3 by inf.animateFloat(0f, 360f, infiniteRepeatable(tween((13000 * speed).toInt(), easing = LinearEasing)), label = "t3")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val target = 1f + 0.16f * level.coerceIn(0f, 1f) + (if (phase == Phase.Thinking) 0.04f * pulse else 0f)
    val scale by animateFloatAsState(target, Motion.pop(), label = "scale")

    Canvas(modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }) {
        val r = this.size.minDimension / 2f
        val c = center
        clipPath(Path().apply { addOval(Rect(c, r)) }) {
            drawCircle(Color(0xFF0B1020), r, c)
            blob(PrismColors.SiriBlue, t1, 0.42f, r, c)
            blob(PrismColors.SiriPink, t2, 0.48f, r, c)
            blob(PrismColors.SiriCyan, t3, 0.38f, r, c)
            blob(PrismColors.SiriOrange, -t2 * 0.7f, 0.34f, r, c)
            blob(PrismColors.SiriPurple, -t1 * 0.5f + 120f, 0.30f, r, c)
        }
        drawCircle(
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.35f))),
            r - 1.dp.toPx(), c, style = Stroke(1.5.dp.toPx()),
        )
        drawOval(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent), center = Offset(c.x - r * 0.3f, c.y - r * 0.5f), radius = r * 0.55f),
            topLeft = Offset(c.x - r * 0.75f, c.y - r * 0.9f),
            size = Size(r * 0.9f, r * 0.55f),
        )
    }
}

private fun DrawScope.blob(color: Color, angleDeg: Float, dist: Float, r: Float, c: Offset) {
    val a = Math.toRadians(angleDeg.toDouble())
    val p = Offset(c.x + (r * dist * cos(a)).toFloat(), c.y + (r * dist * sin(a)).toFloat())
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0f)), center = p, radius = r * 0.8f),
        radius = r * 0.8f, center = p, blendMode = BlendMode.Plus,
    )
}

/** Three dots that swell in sequence — the universal "working on it". */
@Composable
fun ThinkingDots(modifier: Modifier = Modifier, color: Color = PrismColors.TextSecondary, dot: Dp = 7.dp) {
    if (Appearance.nothing) { DotLoader(modifier, color, dot * 0.6f); return }
    val inf = rememberInfiniteTransition(label = "dots")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "t")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val phase = ((t - i * 0.18f) + 1f) % 1f
            val s = 0.6f + 0.4f * sin(phase * Math.PI).toFloat().coerceAtLeast(0f)
            Box(
                Modifier
                    .size(dot)
                    .graphicsLayer { scaleX = s; scaleY = s; alpha = 0.45f + 0.55f * s },
            ) { Canvas(Modifier.fillMaxSize()) { drawCircle(color) } }
            if (i < 2) Spacer(Modifier.width(5.dp))
        }
    }
}
