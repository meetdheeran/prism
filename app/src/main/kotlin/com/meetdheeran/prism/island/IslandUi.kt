package com.meetdheeran.prism.island

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetdheeran.prism.ui.glass.GlassStyle
import com.meetdheeran.prism.ui.glass.LensRegistry
import com.meetdheeran.prism.ui.glass.LocalLens
import com.meetdheeran.prism.ui.glass.RefractionSurface
import com.meetdheeran.prism.ui.glass.lens
import com.meetdheeran.prism.core.IslandStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.Color as CColor
import com.meetdheeran.prism.ui.glass.backdropSource
import com.meetdheeran.prism.ui.glass.liquidGlass
import com.meetdheeran.prism.ui.glass.rememberBackdropState
import com.meetdheeran.prism.ui.motion.LocalTilt
import com.meetdheeran.prism.ui.motion.Motion
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * The pill. Geometry follows the OnePlus 7's waterdrop cutout (about 64 dp wide, 28 dp tall,
 * at the very top): collapsed it just swallows the notch; with content it stretches sideways;
 * tapped it pops into a card. Black glass so it merges with the cutout.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IslandUi(state: IslandState, onToggleExpand: () -> Unit, onAssistant: () -> Unit, onActivityTap: (LiveActivity) -> Unit) {
    val backdrop = rememberBackdropState()
    val mode = when {
        state.expanded && state.media != null -> Mode.EXPANDED
        state.activity?.kind == LiveActivity.Kind.CALL -> Mode.CALL
        state.showEvent -> Mode.EVENT
        state.showChargeBloom -> Mode.CHARGING
        state.activity != null -> Mode.ACTIVITY
        state.media != null -> Mode.MEDIA
        else -> Mode.EMPTY
    }
    val base = state.pillWidthDp.dp
    val baseH = state.pillHeightDp.dp
    val targetW = when (mode) { Mode.EMPTY -> base; Mode.MEDIA -> base + 64.dp; Mode.CHARGING -> base + 86.dp; Mode.EVENT -> base + 124.dp; Mode.CALL, Mode.ACTIVITY -> base + 100.dp; Mode.EXPANDED -> 348.dp }
    val targetH = when (mode) { Mode.EXPANDED -> 156.dp; else -> baseH }
    val w by animateDpAsState(targetW, Motion.pop(), label = "w")
    val h by animateDpAsState(targetH, Motion.pop(), label = "h")
    val shape = RoundedCornerShape(if (mode == Mode.EXPANDED) 34.dp else baseH / 2)

    val lensMode = state.style == IslandStyle.LENS
    val lensRegistry = remember { LensRegistry() }
    val radiusPx = with(LocalDensity.current) { (if (mode == Mode.EXPANDED) 34.dp else baseH / 2).toPx() }
    val fallback = remember(state.media?.art) { lensFallback(state.media?.art) }
    Box(Modifier.padding(bottom = 8.dp, start = 8.dp, end = 8.dp)) {
        if (lensMode) {
            // iOS-27 lens: a transparent GL window that magnifies the strip of screen behind the notch.
            CompositionLocalProvider(LocalLens provides lensRegistry) {
                Box(Modifier.size(w, h).clip(shape)) {
                    val bmp = state.lensBitmap ?: fallback
                    RefractionSurface(
                        bitmap = bmp, registry = lensRegistry, tilt = LocalTilt.current, reveal = 1f, dim = 0f,
                        modifier = Modifier.fillMaxSize().lens("island", radiusPx),
                        texRect = if (state.lensBitmap != null) state.lensRect else null,
                        textureIsSelf = state.lensBitmap == null,
                        magnify = 1.22f, streak = 1f, clarity = if (state.lensBitmap != null) 0.78f else 0.2f, outsideAlpha = 0f,
                    )
                }
            }
        } else {
            // The glass needs something to look through; for a black pill that is a black sheet.
            Box(Modifier.size(w, h).background(Color.Black, shape).backdropSource(backdrop))
        }
        Box(
            Modifier
                .size(w, h)
                .liquidGlass(backdrop, shape, if (lensMode) GlassStyle.Island.copy(backdropless = true, tintAlpha = 0f, highlightAlpha = 0.10f, rimAlpha = 0.85f, innerShadowAlpha = 0f, elevation = 6.dp) else GlassStyle.Island, LocalTilt.current)
                .pointerInput(mode) {
                    detectTapGestures(
                        onTap = { if (mode == Mode.CALL || mode == Mode.ACTIVITY) state.activity?.let(onActivityTap) else if (state.media != null) onToggleExpand() },
                        onLongPress = { onAssistant() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(mode, transitionSpec = { fadeIn(Motion.fade(200)) togetherWith fadeOut(Motion.fade(120)) }, label = "island") { m ->
                when (m) {
                    Mode.EMPTY -> Box(Modifier.fillMaxSize())
                    Mode.MEDIA -> state.media?.let { Collapsed(it) }
                    Mode.CHARGING -> Charging(state.battery)
                    Mode.EVENT -> state.event?.let { EventRow(it) }
                    Mode.CALL -> state.activity?.let { CallRow(it) }
                    Mode.ACTIVITY -> state.activity?.let { ActivityRow(it) }
                    Mode.EXPANDED -> state.media?.let { Expanded(it) }
                }
            }
        }
    }
}

private enum class Mode { EMPTY, MEDIA, CHARGING, EVENT, CALL, ACTIVITY, EXPANDED }

@Composable
private fun Collapsed(np: NowPlaying) {
    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(np, 20.dp, CircleShape)
        Spacer(Modifier.weight(1f))
        AudioBars(playing = np.isPlaying)
    }
}

@Composable
private fun Art(np: NowPlaying, size: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    val art = np.art
    Box(Modifier.size(size).clip(shape).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
private fun AudioBars(playing: Boolean, color: Color = PrismColors.SiriCyan) {
    val inf = rememberInfiniteTransition(label = "bars")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Canvas(Modifier.width(22.dp).height(16.dp)) {
        val n = 4
        val gap = 2.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val phase = t * 6.283f + i * 1.3f
            val frac = if (playing) 0.3f + 0.7f * ((sin(phase) + 1f) / 2f) else 0.25f
            val bh = size.height * frac
            drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(i * (bw + gap), size.height - bh), size = androidx.compose.ui.geometry.Size(bw, bh), cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2))
        }
    }
}

@Composable
private fun Charging(b: BatteryInfo) {
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Bolt, null, tint = PrismColors.Good, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Charging", style = PrismTypography.labelMedium, color = Color.White)
        Spacer(Modifier.weight(1f))
        Text(if (b.percent >= 0) "${b.percent}%" else "", style = PrismTypography.labelMedium, color = PrismColors.Good)
    }
}

@Composable
private fun EventRow(e: IslandEvent) {
    val (icon, tint) = when (e.kind) {
        IslandEvent.Kind.BATTERY_LOW -> Icons.Rounded.BatteryAlert to PrismColors.Bad
        IslandEvent.Kind.WIFI -> Icons.Rounded.Wifi to PrismColors.SiriCyan
        IslandEvent.Kind.BLUETOOTH -> Icons.Rounded.Bluetooth to PrismColors.SiriBlue
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(e.text, style = PrismTypography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CallRow(a: LiveActivity) {
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Call, null, tint = PrismColors.Good, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(a.title.ifBlank { "Call" }, style = PrismTypography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Elapsed(a.whenMs)
    }
}

@Composable
private fun ActivityRow(a: LiveActivity) {
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (a.kind == LiveActivity.Kind.NAVIGATION) Icons.Rounded.Navigation else Icons.Rounded.HourglassBottom, null, tint = PrismColors.SiriOrange, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text((a.title.ifBlank { a.text }).ifBlank { "Activity" }, style = PrismTypography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (a.chronometerBase > 0) { Spacer(Modifier.width(6.dp)); Chrono(a.chronometerBase, a.countDown) }
    }
}

@Composable
private fun Elapsed(sinceWallMs: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val s = ((now - sinceWallMs) / 1000).coerceAtLeast(0)
    Text("%d:%02d".format(s / 60, s % 60), style = PrismTypography.labelMedium, color = PrismColors.Good)
}

@Composable
private fun Chrono(baseWallMs: Long, countDown: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val s = (if (countDown) (baseWallMs - now) else (now - baseWallMs)) / 1000
    val a = s.coerceAtLeast(0)
    Text(if (a >= 3600) "%d:%02d:%02d".format(a / 3600, (a % 3600) / 60, a % 60) else "%d:%02d".format(a / 60, a % 60), style = PrismTypography.labelMedium, color = PrismColors.SiriOrange)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Expanded(np: NowPlaying) {
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(np.isPlaying) { while (true) { delay(500); tick = SystemClock.elapsedRealtime() } }
    val pos = np.livePosition(tick)
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Art(np, 54.dp, RoundedCornerShape(12.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(np.title, style = PrismTypography.titleSmall, color = Color.White, maxLines = 1, modifier = Modifier.basicMarquee())
                Text(np.artist, style = PrismTypography.bodySmall, color = PrismColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            AudioBars(np.isPlaying)
        }
        Spacer(Modifier.height(10.dp))
        val frac = if (np.durationMs > 0) (pos.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(frac).height(4.dp).background(Color.White))
        }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            Text(fmt(pos), fontSize = 10.sp, color = PrismColors.TextTertiary)
            Spacer(Modifier.weight(1f))
            Text("-" + fmt((np.durationMs - pos).coerceAtLeast(0)), fontSize = 10.sp, color = PrismColors.TextTertiary)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(34.dp).pressable { MediaWatcher.previous() })
            Spacer(Modifier.width(26.dp))
            Icon(if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(40.dp).pressable { MediaWatcher.toggle() })
            Spacer(Modifier.width(26.dp))
            Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(34.dp).pressable { MediaWatcher.next() })
        }
    }
}

private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }


/** Without a Shizuku snapshot the lens looks through album art or a deep tint. */
private fun lensFallback(art: Bitmap?): Bitmap {
    val w = 128; val h = 64
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF1B2450.toInt(), 0xFF0B1020.toInt(), 0xFF2A1440.toInt()), null, Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    if (art != null) {
        val small = Bitmap.createScaledBitmap(art, 12, 6, true)
        val big = Bitmap.createScaledBitmap(small, w, h, true)
        c.drawBitmap(big, 0f, 0f, Paint().apply { alpha = 170 })
    }
    return out
}
