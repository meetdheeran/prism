package com.meetdheeran.prism.control

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meetdheeran.prism.island.MediaWatcher
import com.meetdheeran.prism.ui.glass.BackdropState
import com.meetdheeran.prism.ui.glass.GlassBackground
import com.meetdheeran.prism.ui.glass.GlassStyle
import com.meetdheeran.prism.ui.glass.LiquidGlass
import com.meetdheeran.prism.ui.glass.backdropSource
import com.meetdheeran.prism.ui.glass.rememberBackdropState
import com.meetdheeran.prism.ui.motion.Motion
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.siri.Chip
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.coroutines.delay

/**
 * The glass control center. Layout echoes iOS: a wide media card, a 2x2 connectivity cluster,
 * two vertical sliders, then a grid of small tiles. Every tile springs in with a small stagger.
 */
@Composable
fun ControlPanel(control: TileControl, tiles: List<TileSpec>, screenshot: Bitmap?, visible: Boolean, onClose: () -> Unit) {
    val backdrop = rememberBackdropState()
    val accent = LocalAccent.current
    val on by control.on.collectAsState()
    val brightness by control.brightness.collectAsState()
    val volume by control.volume.collectAsState()
    val message by control.message.collectAsState()
    val np by MediaWatcher.now.collectAsState()
    LaunchedEffect(message) { if (message != null) { delay(2800); control.message.value = null } }
    val scrim by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "scrim")

    Box(Modifier.fillMaxSize()) {
        // Backdrop: the frozen screenshot (dimmed) or the animated glass background.
        if (screenshot != null) {
            Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
                Image(screenshot.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f * scrim)))
            }
        } else {
            GlassBackground(backdrop, accent = accent)
        }
        // Tap on empty space closes.
        Box(Modifier.fillMaxSize().clickable(androidx.compose.runtime.remember { MutableInteractionSource() }, null, onClick = onClose))

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            var index = 0
            val visibleIds = tiles.map { it.id }.toSet()
            val gap = 10.dp

            if ("media" in visibleIds) {
                Stagger(visible, index++) { MediaCard(backdrop, np, Modifier.fillMaxWidth().height(150.dp)) }
                Spacer(Modifier.height(gap))
            }

            val cluster = listOf("wifi", "data", "bluetooth", "airplane").filter { it in visibleIds }
            val hasSliders = "brightness" in visibleIds || "volume" in visibleIds
            if (cluster.isNotEmpty() || hasSliders) {
                Row(Modifier.fillMaxWidth().height(164.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    if (cluster.isNotEmpty()) {
                        Stagger(visible, index++, Modifier.weight(2f).fillMaxSize()) {
                            LiquidGlass(backdrop, Modifier.fillMaxSize(), RoundedCornerShape(24.dp), GlassStyle.Regular) {
                                Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    cluster.chunked(2).forEach { row ->
                                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            row.forEach { id -> val t = Tiles.byId(id)!!; RoundTile(t, on[id] == true, Modifier.weight(1f).fillMaxSize()) { control.toggle(id) } }
                                            if (row.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if ("brightness" in visibleIds) Stagger(visible, index++, Modifier.weight(1f).fillMaxSize()) {
                        VerticalSlider(backdrop, brightness, Icons.Rounded.LightMode, Modifier.fillMaxSize()) { control.setBrightness(it) }
                    }
                    if ("volume" in visibleIds) Stagger(visible, index++, Modifier.weight(1f).fillMaxSize()) {
                        VerticalSlider(backdrop, volume, Icons.Rounded.VolumeUp, Modifier.fillMaxSize()) { control.setVolume(it) }
                    }
                }
                Spacer(Modifier.height(gap))
            }

            val rest = tiles.filter { it.id !in setOf("media", "wifi", "data", "bluetooth", "airplane", "brightness", "volume") }
            rest.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { t ->
                        Stagger(visible, index++, Modifier.weight(1f)) {
                            SmallTile(backdrop, t, on[t.id] == true) {
                                if (t.kind == TileSpec.Kind.TOGGLE) control.toggle(t.id) else control.action(t.id, onClose)
                            }
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(gap))
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(44.dp).height(5.dp).background(Color.White.copy(alpha = 0.4f), CircleShape).pressable(onClick = onClose))
            }
        }

        AnimatedVisibility(message != null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp), enter = fadeIn(), exit = fadeOut()) {
            LiquidGlass(backdrop, shape = RoundedCornerShape(16.dp), style = GlassStyle.Dark) {
                Text(message ?: "", style = PrismTypography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

/** Fade + rise + scale with an index-based delay: the panel assembles itself. */
@Composable
private fun Stagger(visible: Boolean, index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 420 else 180, delayMillis = if (visible) index * 22 else 0, easing = Motion.EaseOutExpo), label = "stagger")
    Box(modifier.graphicsLayer { alpha = t; scaleX = 0.86f + 0.14f * t; scaleY = 0.86f + 0.14f * t; translationY = -28f * (1f - t) }) { content() }
}

@Composable
private fun RoundTile(t: TileSpec, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val fill by animateFloatAsState(if (on) 1f else 0f, Motion.snappy(), label = "fill")
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.10f + 0.05f * fill))
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.85f * fill)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(t.icon ?: Icons.Rounded.Apps, t.label, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(t.label, style = PrismTypography.labelSmall, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SmallTile(backdrop: BackdropState, t: TileSpec, on: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val fill by animateFloatAsState(if (on && t.kind == TileSpec.Kind.TOGGLE) 1f else 0f, Motion.snappy(), label = "fill")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LiquidGlass(
            backdrop, Modifier.fillMaxWidth().height(72.dp).pressable(onClick = onClick), RoundedCornerShape(20.dp),
            GlassStyle.Tile.copy(tint = if (fill > 0.5f) accent else Color.White, tintAlpha = 0.12f + 0.6f * fill),
        ) {
            Icon(t.icon ?: (if (t.id == "lock") Icons.Rounded.Lock else Icons.Rounded.Apps), t.label, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(26.dp))
            if (t.needsShizuku) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(6.dp).background(PrismColors.SiriOrange, CircleShape))
        }
        Spacer(Modifier.height(4.dp))
        Text(t.label, style = PrismTypography.labelSmall, color = PrismColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VerticalSlider(backdrop: BackdropState, value: Float, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onChange: (Float) -> Unit) {
    LiquidGlass(backdrop, modifier, RoundedCornerShape(24.dp), GlassStyle.Regular) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, drag ->
                        change.consume()
                        onChange(value - drag / size.height)
                    }
                }
                .pointerInput(Unit) {
                    androidx.compose.foundation.gestures.detectTapGestures { p -> onChange(1f - p.y / size.height) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val h = size.height * value.coerceIn(0f, 1f)
                drawRect(Color.White.copy(alpha = 0.85f), topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
            }
            Icon(icon, null, tint = if (value > 0.18f) Color.Black.copy(alpha = 0.7f) else Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).size(24.dp))
        }
    }
}

@Composable
private fun MediaCard(backdrop: BackdropState, np: com.meetdheeran.prism.island.NowPlaying?, modifier: Modifier) {
    LiquidGlass(backdrop, modifier, RoundedCornerShape(24.dp), GlassStyle.Regular) {
        if (np == null) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.MusicNote, null, tint = PrismColors.TextSecondary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(6.dp))
                Text(if (MediaWatcher.hasAccess(androidx.compose.ui.platform.LocalContext.current)) "Nothing playing" else "Grant notification access to show music", style = PrismTypography.bodySmall, color = PrismColors.TextSecondary)
            }
        } else {
            Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    np.art?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        ?: Icon(Icons.Rounded.MusicNote, null, tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(np.title, style = PrismTypography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(np.artist, style = PrismTypography.bodySmall, color = PrismColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Chip(np.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() })
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(30.dp).pressable { MediaWatcher.previous() })
                        Spacer(Modifier.width(18.dp))
                        Icon(if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(38.dp).pressable { MediaWatcher.toggle() })
                        Spacer(Modifier.width(18.dp))
                        Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(30.dp).pressable { MediaWatcher.next() })
                    }
                }
            }
        }
    }
}
