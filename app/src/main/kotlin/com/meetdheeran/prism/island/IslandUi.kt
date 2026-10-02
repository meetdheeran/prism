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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import kotlin.math.PI
import kotlin.math.sin
import com.meetdheeran.prism.agent.Agent
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.mutableStateOf
import com.meetdheeran.prism.ui.theme.Appearance
import com.meetdheeran.prism.ui.theme.NothingPalette
import com.meetdheeran.prism.ui.theme.NothingFonts
import com.meetdheeran.prism.ui.nothing.GlyphMatrix
import com.meetdheeran.prism.ui.siri.Orb
import com.meetdheeran.prism.ui.siri.Phase

/**
 * The pill. Geometry follows the OnePlus 7's waterdrop cutout (about 64 dp wide, 28 dp tall,
 * at the very top): collapsed it just swallows the notch; with content it stretches sideways;
 * tapped it pops into a card. Black glass so it merges with the cutout.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IslandUi(state: IslandState, onTap: () -> Unit = {}, onToggleExpand: () -> Unit, onAssistant: () -> Unit, onActivityTap: (LiveActivity) -> Unit, onPeekTap: (ShadeItem) -> Unit = {}, onAgentStop: () -> Unit = {}, onAgentAnswer: (Boolean) -> Unit = {}, onAgentTap: () -> Unit = {}, onAgentResultTap: () -> Unit = {}) {
    val backdrop = rememberBackdropState()
    val mode = when {
        state.agent.confirm != null -> Mode.AGENT_CONFIRM
        state.showFace -> Mode.FACE
        state.showUnlock -> Mode.UNLOCK
        state.expanded && state.media != null -> Mode.EXPANDED
        state.agent.running && state.agentPanel -> Mode.AGENT_PANEL
        state.agent.running -> Mode.AGENT
        state.showAgentResult -> Mode.AGENT_RESULT
        state.activity?.kind == LiveActivity.Kind.CALL -> Mode.CALL
        state.showAi -> Mode.AI
        state.showPeek -> Mode.PEEK
        state.showEvent -> Mode.EVENT
        state.showChargeBloom -> Mode.CHARGING
        state.activity != null -> Mode.ACTIVITY
        state.media != null -> Mode.MEDIA
        else -> Mode.EMPTY
    }
    // In the notch: a tab exactly over the camera cutout (the pill's fine-tune doesn't apply); otherwise the pill.
    val tab = state.notch
    val base = if (tab) state.cutWidthDp.dp else state.pillWidthDp.dp
    val baseH = if (tab) (state.cutHeightDp + 1f).dp else state.pillHeightDp.dp
    val targetW = when (mode) { Mode.EMPTY -> base; Mode.MEDIA -> base + 64.dp; Mode.CHARGING -> base + 86.dp; Mode.EVENT -> base + 124.dp; Mode.CALL, Mode.ACTIVITY -> base + 100.dp; Mode.AI -> base + 116.dp; Mode.PEEK -> base + 176.dp; Mode.AGENT -> base + 150.dp; Mode.UNLOCK, Mode.FACE -> base + 12.dp; Mode.EXPANDED, Mode.AGENT_CONFIRM, Mode.AGENT_PANEL, Mode.AGENT_RESULT -> 348.dp }
    val resultLines = state.agent.result.orEmpty().let { r -> r.lines().sumOf { 1 + it.length / 34 } }.coerceIn(1, 9)
    val targetH = when (mode) {
        Mode.EXPANDED -> 156.dp
        Mode.AGENT_CONFIRM -> 158.dp
        Mode.AGENT_PANEL -> 176.dp
        Mode.AGENT_RESULT -> 74.dp + 22.dp * resultLines
        Mode.UNLOCK, Mode.FACE -> baseH + 34.dp
        else -> baseH
    }
    val islandSpring = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.72f, stiffness = 380f * Appearance.islandSpeed * Appearance.islandSpeed)
    val w by animateDpAsState(targetW, islandSpring, label = "w")
    val h by animateDpAsState(targetH, islandSpring, label = "h")
    val big = mode == Mode.EXPANDED || mode == Mode.AGENT_CONFIRM || mode == Mode.AGENT_PANEL || mode == Mode.AGENT_RESULT
    val corner = when { big -> 34.dp; mode == Mode.UNLOCK || mode == Mode.FACE -> 26.dp; else -> baseH / 2 }
    // The tab is flush with the screen's top edge: square on top, rounded below.
    val shape = if (tab) RoundedCornerShape(0.dp, 0.dp, corner, corner) else RoundedCornerShape(corner)

    // The Nothing look has no glass: its island is always a plain black pill.
    val lensMode = state.style == IslandStyle.LENS && !Appearance.nothing && !tab
    val lensRegistry = remember { LensRegistry() }
    val radiusPx = with(LocalDensity.current) { (if (big) 34.dp else baseH / 2).toPx() }
    val fallback = remember(state.media?.art) { lensFallback(state.media?.art) }
    // Split: while the pill shows one thing, a second (music, or a timer) pops off into its own bubble,
    // like the iPhone 18 Pro island. The two black shapes are drawn through a blur + alpha threshold
    // (a "metaball"), so while they're close a liquid neck stretches between them and then snaps.
    val secondary = if (!state.split) null else when {
        mode == Mode.EXPANDED || mode == Mode.EMPTY || mode == Mode.MEDIA || mode == Mode.AGENT_CONFIRM || mode == Mode.AGENT_PANEL || mode == Mode.AGENT_RESULT || mode == Mode.UNLOCK || mode == Mode.FACE -> null
        state.media != null -> Second.MEDIA
        state.activity != null && mode != Mode.ACTIVITY && state.activity.kind != LiveActivity.Kind.CALL -> Second.TIMER
        else -> null
    }
    val speed = Appearance.islandSpeed
    val split by animateFloatAsState(
        if (secondary != null) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 360f * speed * speed), label = "split",
    )
    val lastSecondary = remember { mutableStateOf<Second?>(null) }
    if (secondary != null) lastSecondary.value = secondary
    val splitting = split > 0.002f
    val bubble = baseH
    val gap = 8.dp
    // Equal room on both sides keeps the pill centred on the camera while the bubble sits right.
    val side = if (splitting) bubble + gap + 6.dp else 0.dp
    val density = LocalDensity.current

    // The tab's shoulders: little inward curves where it meets the top edge, like a real notch.
    val shoulder = if (tab) 6.dp else 0.dp
    val outer = if (tab) Modifier.padding(start = shoulder, end = shoulder, bottom = 2.dp) else Modifier.padding(bottom = 8.dp, start = 8.dp, end = 8.dp)
    Box(outer.width(w + side * 2).height(h)) {
        if (tab) {
            Canvas(Modifier.matchParentSize()) {
                val s = shoulder.toPx()
                val l = side.toPx()
                val r = l + w.toPx()
                drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(l - s, 0f); lineTo(l + 1f, 0f); lineTo(l + 1f, s)
                    arcTo(androidx.compose.ui.geometry.Rect(l - 2 * s, 0f, l, 2 * s), 0f, -90f, false)
                    close()
                }, Color.Black)
                drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(r + s, 0f); lineTo(r - 1f, 0f); lineTo(r - 1f, s)
                    arcTo(androidx.compose.ui.geometry.Rect(r, 0f, r + 2 * s, 2 * s), 180f, 90f, false)
                    close()
                }, Color.Black)
            }
        }
        if (splitting) {
            val gooey = remember(density) { gooeyEffect(with(density) { 7.dp.toPx() }) }
            Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = gooey }) {
                val left = side.toPx()
                // The tab's top corners go above the screen edge, so it stays flush with the top.
                if (tab) drawRoundRect(Color.Black, Offset(left, -h.toPx() / 2), Size(w.toPx(), h.toPx() * 1.5f), CornerRadius(h.toPx() / 2))
                else drawRoundRect(Color.Black, Offset(left, 0f), Size(w.toPx(), h.toPx()), CornerRadius(h.toPx() / 2))
                val r = bubble.toPx() / 2 * (0.55f + 0.45f * split.coerceIn(0f, 1.2f))
                // From tucked inside the pill's right end out to its resting spot, overshooting on the spring.
                val cx = left + w.toPx() - bubble.toPx() / 2 + (bubble + gap).toPx() * split
                drawCircle(Color.Black, r, Offset(cx, h.toPx() / 2))
            }
        }
        Box(Modifier.offset(x = side)) {
            PillBody(state, mode, w, h, shape, lensMode, lensRegistry, radiusPx, fallback, backdrop, splitting, onTap, onToggleExpand, onAssistant, onActivityTap, onPeekTap, onAgentStop, onAgentAnswer, onAgentTap, onAgentResultTap)
        }
        if (splitting) {
            val sec = secondary ?: lastSecondary.value
            val x = side + w - bubble + (bubble + gap) * split
            Box(
                Modifier
                    .offset(x = x)
                    .size(bubble)
                    .graphicsLayer {
                        alpha = ((split - 0.55f) / 0.45f).coerceIn(0f, 1f)
                        val sc = 0.8f + 0.2f * split.coerceAtMost(1f)
                        scaleX = sc; scaleY = sc
                    }
                    .pointerInput(sec) {
                        detectTapGestures(
                            onTap = { if (sec == Second.MEDIA) onToggleExpand() else state.activity?.let(onActivityTap) },
                            onLongPress = { onAssistant() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (sec) {
                    Second.MEDIA -> state.media?.let { np -> if (np.art != null && !Appearance.nothing) Art(np, bubble - 12.dp, CircleShape) else AudioBars(np.isPlaying) }
                    Second.TIMER -> state.activity?.let { a ->
                        if (a.chronometerBase > 0) MiniChrono(a.chronometerBase, a.countDown)
                        else Icon(Icons.Rounded.HourglassBottom, null, tint = Ink.warm, modifier = Modifier.size(14.dp))
                    }
                    null -> Unit
                }
            }
        }
    }
}

private enum class Second { MEDIA, TIMER }

/** Blur, then push alpha through a steep ramp: soft blobs become one hard shape with liquid necks. */
private fun gooeyEffect(blurPx: Float): androidx.compose.ui.graphics.RenderEffect {
    val threshold = android.graphics.ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 28f, -28f * 128f,
    ))
    val blur = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.DECAL)
    return android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(threshold), blur).asComposeRenderEffect()
}

/** A compact countdown for the bubble: minutes, or seconds under a minute. */
@Composable
private fun MiniChrono(baseWallMs: Long, countDown: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val s = ((if (countDown) (baseWallMs - now) else (now - baseWallMs)) / 1000).coerceAtLeast(0)
    Text(if (s >= 60) "${s / 60}m" else "${s}s", style = PrismTypography.labelSmall, color = Ink.warm, maxLines = 1)
}

/** The main pill: black glass (or lens), or just content when split (the gooey layer draws its shape). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PillBody(
    state: IslandState, mode: Mode, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape, lensMode: Boolean, lensRegistry: LensRegistry, radiusPx: Float, fallback: Bitmap,
    backdrop: com.meetdheeran.prism.ui.glass.BackdropState, bare: Boolean,
    onTap: () -> Unit, onToggleExpand: () -> Unit, onAssistant: () -> Unit, onActivityTap: (LiveActivity) -> Unit, onPeekTap: (ShadeItem) -> Unit,
    onAgentStop: () -> Unit, onAgentAnswer: (Boolean) -> Unit, onAgentTap: () -> Unit, onAgentResultTap: () -> Unit,
) {
    Box {
        if (bare) {
            // Split: the gooey layer behind already draws this pill's black shape.
        } else if (lensMode) {
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
                // The tab is plain black, to be one with the camera cutout.
                .then(if (bare || state.notch) Modifier else Modifier.liquidGlass(backdrop, shape, if (lensMode) GlassStyle.Island.copy(backdropless = true, tintAlpha = 0f, highlightAlpha = 0.10f, rimAlpha = 0.85f, innerShadowAlpha = 0f, elevation = 6.dp) else GlassStyle.Island, LocalTilt.current))
                .pointerInput(mode) {
                    // Swiping down on the island opens it (music), and never reaches the status bar under it.
                    var pulled = 0f
                    detectVerticalDragGestures(
                        onDragStart = { pulled = 0f },
                        onDragEnd = { if (pulled > 36.dp.toPx() && state.media != null && !state.expanded) onToggleExpand() },
                    ) { change, dy -> change.consume(); pulled += dy }
                }
                .pointerInput(mode) {
                    detectTapGestures(
                        onTap = {
                            onTap()
                            when {
                                // Tapping the island while the agent works stops it.
                                // Tap while it works opens the step panel (with Stop); it no longer stops outright.
                                mode == Mode.AGENT -> onAgentTap()
                                mode == Mode.AGENT_RESULT -> onAgentResultTap()
                                mode == Mode.AGENT_CONFIRM || mode == Mode.AGENT_PANEL || mode == Mode.UNLOCK || mode == Mode.FACE -> Unit
                                mode == Mode.CALL || mode == Mode.ACTIVITY -> state.activity?.let(onActivityTap)
                                mode == Mode.PEEK -> state.peek?.let(onPeekTap)
                                // The tab is always in the notch, so a stray tap mustn't open anything; hold talks.
                                mode == Mode.EMPTY && state.notch -> Unit
                                mode == Mode.EMPTY || mode == Mode.AI -> onAssistant()
                                state.media != null -> onToggleExpand()
                            }
                        },
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
                    Mode.AI -> AiRow(state.ai, state.aiLevel)
                    Mode.AGENT -> AgentRow(state.agent)
                    Mode.AGENT_CONFIRM -> state.agent.confirm?.let { AgentConfirmCard(it, onAgentAnswer) }
                    Mode.AGENT_RESULT -> AgentResultCard(state.agent)
                    Mode.AGENT_PANEL -> AgentPanelCard(state.agent, onStop = onAgentStop, onHide = onAgentTap)
                    Mode.UNLOCK -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { UnlockGlyph() }
                    Mode.FACE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FaceScanGlyph(state.face) }
                    Mode.PEEK -> state.peek?.let { PeekRow(it) }
                }
            }
        }
    }
}

private enum class Mode { EMPTY, MEDIA, CHARGING, EVENT, CALL, ACTIVITY, EXPANDED, AI, PEEK, AGENT, AGENT_CONFIRM, AGENT_RESULT, AGENT_PANEL, UNLOCK, FACE }

/** The agent's finished answer, big enough to read: stays 15 s, tap outside to close, tap it to open the chat. */
@Composable
private fun AgentResultCard(a: Agent.State) {
    val nothing = Appearance.nothing
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (a.success) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = if (a.success) Ink.good else Ink.bad, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            val head = if (a.success) "Done" else "Couldn't finish"
            Text(if (nothing) head.uppercase() else head, style = PrismTypography.labelMedium, color = Ink.secondary, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            a.result.orEmpty(),
            style = if (nothing) PrismTypography.bodyMedium.copy(fontFamily = NothingFonts.Mono, fontSize = 13.sp, lineHeight = 19.sp) else PrismTypography.bodyMedium,
            color = Color.White, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(if (nothing) "TAP TO OPEN · TAP OUTSIDE TO CLOSE" else "Tap to open · tap outside to close", style = PrismTypography.labelSmall, color = Ink.tertiary, maxLines = 1)
    }
}

/** Tap on the island while the agent works: what it's doing, and a Stop button (tap outside to hide). */
@Composable
private fun AgentPanelCard(a: Agent.State, onStop: () -> Unit, onHide: () -> Unit) {
    val nothing = Appearance.nothing
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        val head = "Prism agent · step ${a.step}" + if (a.appLabel.isNotBlank()) " · ${a.appLabel}" else ""
        Text(if (nothing) head.uppercase() else head, style = PrismTypography.labelSmall, color = Ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(a.goal, style = PrismTypography.titleSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(a.label.ifBlank { "Working" }, style = PrismTypography.bodySmall, color = Ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val shape = RoundedCornerShape(50)
            Box(
                Modifier.weight(1f).height(38.dp).clip(shape).border(1.dp, Color.White.copy(alpha = 0.3f), shape).pressable(onClick = onHide),
                contentAlignment = Alignment.Center,
            ) { Text(if (nothing) "HIDE" else "Hide", style = PrismTypography.labelLarge, color = Color.White) }
            Box(
                Modifier.weight(1f).height(38.dp).clip(shape).background(if (nothing) NothingPalette.Red else Color(0xFFFF453A)).pressable(onClick = onStop),
                contentAlignment = Alignment.Center,
            ) { Text(if (nothing) "STOP" else "Stop", style = PrismTypography.labelLarge, color = Color.White) }
        }
    }
}

/**
 * The unlock moment, after Face ID on the iPhone: a face glyph (corner brackets, eyes, nose, a smile
 * that draws itself) that turns into a padlock springing open. Dotted strokes in the Nothing look.
 */
@Composable
private fun UnlockGlyph() {
    val t = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(1_450, easing = LinearEasing)) }
    val nothing = Appearance.nothing
    Canvas(Modifier.size(38.dp)) {
        val p = t.value
        val s = size.minDimension
        val sw = s * 0.06f
        val dots = if (nothing) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(0.01f, sw * 1.7f)) else null
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round, pathEffect = dots)
        val ink = Color.White
        val faceAlpha = if (p < 0.55f) 1f else (1f - (p - 0.55f) / 0.12f).coerceIn(0f, 1f)
        val lockAlpha = ((p - 0.6f) / 0.12f).coerceIn(0f, 1f)
        if (faceAlpha > 0f) {
            // Brackets breathe inward while it "scans".
            val scan = if (p < 0.5f) 0.04f * sin(p * 25f) else 0f
            val m = s * (0.06f + scan)
            val len = s * 0.24f
            val r = s * 0.1f
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, y + dy * len); lineTo(x, y + dy * r)
                    quadraticBezierTo(x, y, x + dx * r, y); lineTo(x + dx * len, y)
                }
                drawPath(path, ink.copy(alpha = faceAlpha), style = stroke)
            }
            corner(m, m, 1f, 1f); corner(s - m, m, -1f, 1f); corner(m, s - m, 1f, -1f); corner(s - m, s - m, -1f, -1f)
            // Eyes, nose, and a smile that draws itself.
            drawLine(ink.copy(alpha = faceAlpha), androidx.compose.ui.geometry.Offset(s * 0.36f, s * 0.36f), androidx.compose.ui.geometry.Offset(s * 0.36f, s * 0.44f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(ink.copy(alpha = faceAlpha), androidx.compose.ui.geometry.Offset(s * 0.64f, s * 0.36f), androidx.compose.ui.geometry.Offset(s * 0.64f, s * 0.44f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            val nose = androidx.compose.ui.graphics.Path().apply { moveTo(s * 0.5f, s * 0.38f); lineTo(s * 0.5f, s * 0.56f); lineTo(s * 0.45f, s * 0.56f) }
            drawPath(nose, ink.copy(alpha = faceAlpha), style = stroke)
            val smile = ((p - 0.15f) / 0.3f).coerceIn(0f, 1f)
            if (smile > 0f) drawArc(ink.copy(alpha = faceAlpha), 150f - 120f * smile + 120f, 120f * smile, false,
                topLeft = androidx.compose.ui.geometry.Offset(s * 0.32f, s * 0.44f), size = androidx.compose.ui.geometry.Size(s * 0.36f, s * 0.24f), style = stroke)
        }
        if (lockAlpha > 0f) {
            val open = ((p - 0.72f) / 0.2f).coerceIn(0f, 1f)
            val bodyTop = s * 0.48f
            drawRoundRect(ink.copy(alpha = lockAlpha), topLeft = androidx.compose.ui.geometry.Offset(s * 0.26f, bodyTop), size = androidx.compose.ui.geometry.Size(s * 0.48f, s * 0.36f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f))
            // Shackle: the right leg lifts out of the body as it springs open.
            val lift = s * 0.12f * open
            val shackle = androidx.compose.ui.graphics.Path().apply {
                moveTo(s * 0.36f, bodyTop)
                lineTo(s * 0.36f, s * 0.34f - lift)
                cubicTo(s * 0.36f, s * 0.16f - lift, s * 0.64f, s * 0.16f - lift, s * 0.64f, s * 0.34f - lift)
                lineTo(s * 0.64f, s * 0.40f - lift)
            }
            drawPath(shackle, ink.copy(alpha = lockAlpha), style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawCircle(if (nothing) NothingPalette.Red else Color.Black, s * 0.045f, androidx.compose.ui.geometry.Offset(s * 0.5f, bodyTop + s * 0.16f), alpha = lockAlpha)
        }
    }
}

/**
 * Another app's face check (Morse's face unlock), with no words: the dotted face brackets breathe and a line sweeps
 * while the camera looks; a smile draws itself when it's you; a small red shake when it isn't.
 */
@Composable
private fun FaceScanGlyph(face: FaceScan) {
    val nothing = Appearance.nothing
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "faceScan").animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(tween(850, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "pulse",
    )
    val t = remember(face) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(face) { if (face != FaceScan.SCANNING) t.animateTo(1f, tween(650, easing = LinearEasing)) }
    Canvas(Modifier.size(38.dp)) {
        val p = t.value
        val s = size.minDimension
        val sw = s * 0.06f
        val dots = if (nothing) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(0.01f, sw * 1.7f)) else null
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round, pathEffect = dots)
        val fail = face == FaceScan.FAIL
        val ink = if (fail) (if (nothing) NothingPalette.Red else Color(0xFFFF453A)) else Color.White
        val shake = if (fail) (sin(p * 6f * PI.toFloat()) * s * 0.09f * (1f - p)) else 0f
        val breathe = if (face == FaceScan.SCANNING) 0.035f * pulse else 0f
        val m = s * (0.06f + breathe)
        val len = s * 0.24f
        val r = s * 0.1f
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x + shake, y + dy * len); lineTo(x + shake, y + dy * r)
                quadraticBezierTo(x + shake, y, x + shake + dx * r, y); lineTo(x + shake + dx * len, y)
            }
            drawPath(path, ink, style = stroke)
        }
        corner(m, m, 1f, 1f); corner(s - m, m, -1f, 1f); corner(m, s - m, 1f, -1f); corner(s - m, s - m, -1f, -1f)
        val eyeTop = androidx.compose.ui.geometry.Offset(s * 0.36f + shake, s * 0.36f)
        drawLine(ink, eyeTop, eyeTop.copy(y = s * 0.44f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(ink, eyeTop.copy(x = s * 0.64f + shake), androidx.compose.ui.geometry.Offset(s * 0.64f + shake, s * 0.44f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        when (face) {
            FaceScan.SCANNING -> {
                // A line sweeping down and up the face while the camera looks.
                val y = s * (0.28f + 0.44f * pulse)
                drawLine(ink.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(s * 0.24f, y), androidx.compose.ui.geometry.Offset(s * 0.76f, y), sw * 0.8f, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            FaceScan.OK -> {
                val smile = p.coerceIn(0f, 1f)
                if (smile > 0f) drawArc(ink, 150f - 120f * smile + 120f, 120f * smile, false,
                    topLeft = androidx.compose.ui.geometry.Offset(s * 0.32f, s * 0.44f), size = androidx.compose.ui.geometry.Size(s * 0.36f, s * 0.24f), style = stroke)
            }
            FaceScan.FAIL -> drawLine(ink, androidx.compose.ui.geometry.Offset(s * 0.38f + shake, s * 0.62f), androidx.compose.ui.geometry.Offset(s * 0.62f + shake, s * 0.62f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            FaceScan.OFF -> Unit
        }
    }
}

/** Agent working: a thinking glyph/orb, the current step, and a small stop square (tap anywhere stops). */
@Composable
private fun AgentRow(a: Agent.State) {
    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (Appearance.nothing) GlyphMatrix(phase = Phase.Thinking, size = 20.dp, grid = 11) else Orb(phase = Phase.Thinking, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        val label = a.label.ifBlank { "Working" }
        Text(if (Appearance.nothing) label.uppercase() else label, style = PrismTypography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(16.dp).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(6.dp).background(Color.White, RoundedCornerShape(1.dp)))
        }
    }
}

/**
 * The agent's "may I?" — exactly what will happen, and two buttons. Nothing irreversible happens
 * until Allow is tapped; Cancel (or 90 s of silence) ends the task with nothing sent.
 */
@Composable
private fun AgentConfirmCard(c: Agent.Confirm, onAnswer: (Boolean) -> Unit) {
    val nothing = Appearance.nothing
    val verb = c.text.trim().substringBefore(' ').lowercase().replaceFirstChar { it.uppercase() }
    val allowLabel = if (verb in setOf("Send", "Post", "Delete", "Call", "Share", "Reply", "Submit")) verb else "Allow"
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        val header = "Prism agent · ${c.appLabel}"
        Text(if (nothing) header.uppercase() else header, style = PrismTypography.labelSmall, color = Ink.secondary, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Text(c.text, style = PrismTypography.titleSmall, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val shape = RoundedCornerShape(50)
            Box(
                Modifier.weight(1f).height(38.dp).clip(shape).border(1.dp, Color.White.copy(alpha = 0.3f), shape).pressable { onAnswer(false) },
                contentAlignment = Alignment.Center,
            ) { Text(if (nothing) "CANCEL" else "Cancel", style = PrismTypography.labelLarge, color = Color.White) }
            Box(
                Modifier.weight(1f).height(38.dp).clip(shape).background(if (nothing) NothingPalette.Red else Color(0xFF30D158)).pressable { onAnswer(true) },
                contentAlignment = Alignment.Center,
            ) { Text(if (nothing) allowLabel.uppercase() else allowLabel, style = PrismTypography.labelLarge, color = Color.White) }
        }
    }
}

@Composable
private fun AgentResultRow(a: Agent.State) {
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (a.success) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = if (a.success) Ink.good else Ink.bad, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(a.result.orEmpty(), style = PrismTypography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).basicMarquee())
    }
}

/**
 * The island is black in every look and theme, so its colours are fixed rather than taken from the
 * app palette (which turns black-on-white in the Nothing light theme). Nothing: white + one red.
 */
private object Ink {
    private val n get() = Appearance.nothing
    val good get() = if (n) Color.White else Color(0xFF30D158)
    val warm get() = if (n) NothingPalette.Red else Color(0xFFFF8A3D)
    val cyan get() = if (n) Color.White else Color(0xFF3CC8FF)
    val blue get() = if (n) Color.White else Color(0xFF3B7BFF)
    val bad get() = if (n) NothingPalette.Red else Color(0xFFFF453A)
    val secondary = Color(0xFF9A9A9A)
    val tertiary = Color(0xFF666666)
}

@Composable
private fun AiRow(phase: Phase, level: Float) {
    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (Appearance.nothing) GlyphMatrix(level = level, phase = phase, size = 22.dp, grid = 11)
        else Orb(level = level, phase = phase, size = 22.dp)
        Spacer(Modifier.width(8.dp))
        val label = when (phase) { Phase.Listening -> "Listening"; Phase.Thinking -> "Thinking"; Phase.Speaking -> "Speaking"; Phase.Idle -> "" }
        Text(if (Appearance.nothing) label.uppercase() else label, style = PrismTypography.labelMedium, color = Color.White, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (phase == Phase.Listening) Canvas(Modifier.size(7.dp)) { drawCircle(if (Appearance.nothing) NothingPalette.Red else Color(0xFFFF453A)) }
    }
}

@Composable
private fun PeekRow(n: ShadeItem) {
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = n.icon
        if (icon != null) Image(icon.asImageBitmap(), n.appLabel, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        val who = n.title.ifBlank { n.appLabel }
        val line = if (n.text.isBlank()) who else "$who · ${n.text}"
        Text(line, style = PrismTypography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

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
private fun AudioBars(playing: Boolean, color: Color = Ink.cyan) {
    val inf = rememberInfiniteTransition(label = "bars")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "t")
    if (Appearance.nothing) {
        // Dot-matrix equaliser: four columns of four dots, lit from the bottom.
        Canvas(Modifier.width(22.dp).height(16.dp)) {
            val cols = 4; val rows = 4
            val cw = size.width / cols; val rh = size.height / rows
            val r = minOf(cw, rh) * 0.32f
            for (i in 0 until cols) {
                val frac = if (playing) 0.3f + 0.7f * ((sin(t * 6.283f + i * 1.3f) + 1f) / 2f) else 0.25f
                val lit = (frac * rows).toInt().coerceIn(1, rows)
                for (j in 0 until rows) {
                    val on = (rows - 1 - j) < lit
                    drawCircle(Color.White.copy(alpha = if (on) 1f else 0.18f), r, androidx.compose.ui.geometry.Offset((i + 0.5f) * cw, (j + 0.5f) * rh))
                }
            }
        }
        return
    }
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
        Icon(Icons.Rounded.Bolt, null, tint = Ink.good, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Charging", style = PrismTypography.labelMedium, color = Color.White)
        Spacer(Modifier.weight(1f))
        Text(if (b.percent >= 0) "${b.percent}%" else "", style = PrismTypography.labelMedium, color = Ink.good)
    }
}

@Composable
private fun EventRow(e: IslandEvent) {
    val (icon, tint) = when (e.kind) {
        IslandEvent.Kind.BATTERY_LOW -> Icons.Rounded.BatteryAlert to Ink.bad
        IslandEvent.Kind.WIFI -> Icons.Rounded.Wifi to Ink.cyan
        IslandEvent.Kind.BLUETOOTH -> Icons.Rounded.Bluetooth to Ink.blue
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
        Icon(Icons.Rounded.Call, null, tint = Ink.good, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(a.title.ifBlank { "Call" }, style = PrismTypography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Elapsed(a.whenMs)
    }
}

@Composable
private fun ActivityRow(a: LiveActivity) {
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (a.kind == LiveActivity.Kind.NAVIGATION) Icons.Rounded.Navigation else Icons.Rounded.HourglassBottom, null, tint = Ink.warm, modifier = Modifier.size(16.dp))
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
    Text("%d:%02d".format(s / 60, s % 60), style = PrismTypography.labelMedium, color = Ink.good)
}

@Composable
private fun Chrono(baseWallMs: Long, countDown: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val s = (if (countDown) (baseWallMs - now) else (now - baseWallMs)) / 1000
    val a = s.coerceAtLeast(0)
    Text(if (a >= 3600) "%d:%02d:%02d".format(a / 3600, (a % 3600) / 60, a % 60) else "%d:%02d".format(a / 60, a % 60), style = PrismTypography.labelMedium, color = Ink.warm)
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
                Text(np.artist, style = PrismTypography.bodySmall, color = Ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Text(fmt(pos), fontSize = 10.sp, color = Ink.tertiary)
            Spacer(Modifier.weight(1f))
            Text("-" + fmt((np.durationMs - pos).coerceAtLeast(0)), fontSize = 10.sp, color = Ink.tertiary)
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
