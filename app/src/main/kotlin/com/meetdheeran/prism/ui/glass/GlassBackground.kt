package com.meetdheeran.prism.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.meetdheeran.prism.ui.theme.PrismColors
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import com.meetdheeran.prism.ui.theme.Appearance

/**
 * What lives behind the glass on our own screens: OLED-black with slow, soft colour orbs so the
 * blur, refraction ring and tilt highlight have something to work on. Radial gradients are
 * already soft, so no blur pass is needed here (a full-screen blur at 60 Hz would cost more
 * than everything else on the page).
 */
@Composable
fun GlassBackground(
    state: BackdropState,
    modifier: Modifier = Modifier,
    accent: Color = PrismColors.DefaultAccent,
    animated: Boolean = true,
    intensity: Float = 1f,
    /** false = no black base, so the app underneath stays visible (overlays and sheets). */
    opaque: Boolean = true,
) {
    val t by if (animated) {
        rememberInfiniteTransition(label = "bg").animateFloat(
            0f, 1f, infiniteRepeatable(tween(28_000, easing = LinearEasing), RepeatMode.Restart), label = "t",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.3f) }
    }
    if (Appearance.nothing) {
        NothingBackground(state, modifier, opaque)
        return
    }
    Canvas(
        modifier
            .fillMaxSize()
            .then(if (opaque) Modifier.background(PrismColors.Ink) else Modifier)
            .backdropSource(state),
    ) {
        val w = size.width
        val h = size.height
        val a = t * 6.2831853f
        fun orb(color: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = alpha * intensity), Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r,
                center = Offset(cx, cy),
            )
        }
        orb(accent, w * (0.22f + 0.10f * sin(a)), h * (0.18f + 0.06f * cos(a * 1.3f)), w * 0.75f, 0.42f)
        orb(PrismColors.SiriPurple, w * (0.85f + 0.08f * cos(a * 0.8f)), h * (0.35f + 0.08f * sin(a * 0.7f)), w * 0.7f, 0.30f)
        orb(PrismColors.SiriPink, w * (0.30f + 0.12f * cos(a * 1.1f)), h * (0.80f + 0.06f * sin(a)), w * 0.65f, 0.22f)
        orb(PrismColors.SiriCyan, w * (0.75f + 0.10f * sin(a * 0.6f)), h * (0.92f + 0.04f * cos(a * 0.9f)), w * 0.6f, 0.20f)
    }
}

/** Nothing look: a flat canvas with a faint dot grid, the texture on Nothing's widgets and wallpapers. */
@Composable
private fun NothingBackground(state: BackdropState, modifier: Modifier, opaque: Boolean) {
    val p = Appearance.palette
    Canvas(
        modifier
            .fillMaxSize()
            .then(if (opaque) Modifier.background(p.bg) else Modifier)
            .backdropSource(state),
    ) {
        if (!opaque || !Appearance.dotGrid) return@Canvas
        val step = 18.dp.toPx()
        val r = 1.1.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(p.dot, r, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}
