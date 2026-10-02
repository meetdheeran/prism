package com.meetdheeran.prism.aod

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetdheeran.prism.island.MediaWatcher
import com.meetdheeran.prism.island.PrismNotificationListener
import com.meetdheeran.prism.island.ShadeItem
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.theme.Appearance
import com.meetdheeran.prism.ui.theme.NothingFonts
import com.meetdheeran.prism.ui.theme.NothingPalette
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import kotlin.random.Random

private val White = Color(0xFFF2F2F2)
private val Grey = Color(0xFF8A8A8A)
private val Faint = Color(0xFF3A3A3A)

/**
 * What the always-on display shows, in either look: clock, date, battery, notification icons and
 * music. Always on black (whatever the phone's light/dark mode) — that's what keeps an AOD cheap on
 * OLED. Everything drifts a few dp once a minute so no pixel stays lit in one place.
 */
@Composable
fun AodScreen(settings: com.meetdheeran.prism.core.Settings, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000L * (60 - value.second).coerceAtLeast(1))
        }
    }
    var shift by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(now.minute) { shift = Random.nextInt(-10, 11) to Random.nextInt(-14, 15) }
    val battery = remember(now) { readBattery(ctx) }
    val notes by PrismNotificationListener.shadeItems.collectAsState()
    val media by MediaWatcher.now.collectAsState()
    val nothing = Appearance.nothing
    // Leaving (double-tap): the clock lifts and fades before the lock screen takes over — the first part of the
    // one-take: the home screen picks it up with its unlock animation.
    val leave = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val leaveNow: () -> Unit = {
        scope.launch {
            leave.animateTo(1f, tween(280))
            onDismiss()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { leaveNow() }) },
    ) {
        // Music playing: its cover fills the screen, blurred, dim and slowly drifting (still mostly black for the OLED).
        val art = media?.takeIf { it.isPlaying && settings.aodShowMusic }?.art
        if (art != null) {
            val image = remember(art) { art.asImageBitmap() }
            val drift by rememberInfiniteTransition(label = "aodArt").animateFloat(
                0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "t",
            )
            Image(
                image, null, contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val sc = 1.15f + 0.08f * drift
                        scaleX = sc
                        scaleY = sc
                        translationX = (drift - 0.5f) * 30.dp.toPx()
                        alpha = 0.3f * (1f - leave.value)
                    }
                    .blur(48.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .offset(shift.first.dp, shift.second.dp)
                .graphicsLayer {
                    alpha = 1f - leave.value
                    val sc = 1f - 0.08f * leave.value
                    scaleX = sc
                    scaleY = sc
                    translationY = -28.dp.toPx() * leave.value
                }
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val style = when (settings.aodClockStyle) { "dot", "thin", "bold" -> settings.aodClockStyle; else -> if (nothing) "dot" else "thin" }
            val scale = settings.aodClockScale
            when (style) {
                "dot" -> NothingClock(now, scale, settings.aodShowDate)
                "bold" -> BoldClock(now, scale, settings.aodShowDate, nothing)
                else -> GlassClock(now, scale, settings.aodShowDate)
            }
            Spacer(Modifier.height(10.dp))
            // Charging: the battery as a row of dots filling up, Glyph-style.
            if (settings.aodShowBattery && battery.charging) {
                ChargeDots(battery.percent)
                Spacer(Modifier.height(8.dp))
            }
            if (settings.aodShowBattery) Text(
                battery.label(),
                style = if (nothing) TextStyle(fontFamily = NothingFonts.Mono, fontSize = 12.sp, letterSpacing = 1.sp, color = Grey)
                else TextStyle(fontSize = 14.sp, color = Grey),
            )
            if (settings.aodShowNotifications && notes.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                NotificationIcons(notes)
            }
            val np = media
            if (settings.aodShowMusic && np != null && np.title.isNotBlank()) {
                Spacer(Modifier.height(28.dp))
                MusicLine(np.title, np.artist, np.isPlaying, nothing)
            }
        }
        Text(
            if (nothing) "DOUBLE-TAP TO OPEN" else "Double-tap to open",
            style = if (nothing) TextStyle(fontFamily = NothingFonts.Mono, fontSize = 10.sp, letterSpacing = 1.2.sp, color = Faint) else TextStyle(fontSize = 12.sp, color = Faint),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).offset(shift.first.dp, 0.dp),
        )
    }
}

@Composable
private fun NothingClock(now: LocalDateTime, scale: Float, showDate: Boolean) {
    if (showDate) Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawCircle(NothingPalette.Red) }
        Spacer(Modifier.width(10.dp))
        Text(
            now.format(DateTimeFormatter.ofPattern("EEE dd MMM", Locale.getDefault())).uppercase(),
            style = TextStyle(fontFamily = NothingFonts.Mono, fontSize = 13.sp, letterSpacing = 1.5.sp, color = Grey),
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        now.format(DateTimeFormatter.ofPattern("HH:mm")),
        style = TextStyle(fontFamily = NothingFonts.Dot, fontWeight = FontWeight.Bold, fontSize = 96.sp * scale, lineHeight = 100.sp * scale, color = White),
    )
}

@Composable
private fun GlassClock(now: LocalDateTime, scale: Float, showDate: Boolean) {
    Text(
        now.format(DateTimeFormatter.ofPattern("H:mm")),
        style = TextStyle(fontWeight = FontWeight.Thin, fontSize = 88.sp * scale, letterSpacing = (-2).sp, color = White),
    )
    if (showDate) Text(
        now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Light, color = Grey),
    )
}

/** Big heavy stacked hours over minutes, like a Samsung AOD "bold" clock. */
@Composable
private fun BoldClock(now: LocalDateTime, scale: Float, showDate: Boolean, nothing: Boolean) {
    val family = if (nothing) NothingFonts.Grotesk else null
    val big = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 104.sp * scale, lineHeight = 96.sp * scale, letterSpacing = (-3).sp, color = White)
    Text(now.format(DateTimeFormatter.ofPattern("HH")), style = big)
    Text(now.format(DateTimeFormatter.ofPattern("mm")), style = big.copy(color = if (nothing) NothingPalette.Red else Grey))
    if (showDate) Text(
        now.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).let { if (nothing) it.uppercase() else it },
        style = TextStyle(fontFamily = if (nothing) NothingFonts.Mono else null, fontSize = 14.sp, letterSpacing = 1.sp, color = Grey),
    )
}

/** Up to six monochrome app icons (their status-bar icons), then a count. */
@Composable
private fun NotificationIcons(items: List<ShadeItem>) {
    val byApp = items.distinctBy { it.packageName }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        byApp.take(6).forEach { n ->
            val bmp = n.icon
            if (bmp != null) Image(bmp.asImageBitmap(), n.appLabel, colorFilter = ColorFilter.tint(Grey), modifier = Modifier.size(18.dp))
            else Canvas(Modifier.size(6.dp)) { drawCircle(Grey) }
        }
        if (byApp.size > 6) Text("+${byApp.size - 6}", style = TextStyle(fontSize = 12.sp, color = Grey, fontFamily = if (Appearance.nothing) NothingFonts.Mono else null))
    }
}

@Composable
private fun MusicLine(title: String, artist: String, playing: Boolean, nothing: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (nothing) title.uppercase() else title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = if (nothing) TextStyle(fontFamily = NothingFonts.Mono, fontSize = 13.sp, letterSpacing = 0.8.sp, color = White) else TextStyle(fontSize = 15.sp, color = White),
            modifier = Modifier.widthIn(max = 280.dp),
        )
        if (artist.isNotBlank()) Text(
            artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = if (nothing) TextStyle(fontFamily = NothingFonts.Mono, fontSize = 11.sp, color = Grey) else TextStyle(fontSize = 13.sp, color = Grey),
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            AodIcon(Icons.Rounded.SkipPrevious, "Previous") { MediaWatcher.previous() }
            AodIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") { MediaWatcher.toggle() }
            AodIcon(Icons.Rounded.SkipNext, "Next") { MediaWatcher.next() }
        }
    }
}

@Composable
private fun AodIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Icon(icon, label, tint = Grey, modifier = Modifier.size(26.dp).pressable(onClick = onClick))
}

private data class Battery(val percent: Int, val charging: Boolean) {
    fun label(): String {
        val nothing = Appearance.nothing
        val pct = if (percent >= 0) "$percent%" else ""
        return when {
            charging && nothing -> "CHARGING · $pct"
            charging -> "Charging · $pct"
            nothing -> "BATTERY · $pct"
            else -> pct
        }
    }
}

private fun readBattery(ctx: Context): Battery {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return Battery(-1, false)
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
    return Battery(pct, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
}

/** Ten dots, lit for each tenth of the battery; the next one to fill blinks gently. */
@Composable
private fun ChargeDots(percent: Int) {
    val lit = (percent.coerceIn(0, 100) + 5) / 10
    val blink by rememberInfiniteTransition(label = "charge").animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Reverse), label = "b",
    )
    Canvas(Modifier.size(width = 130.dp, height = 8.dp)) {
        val step = size.width / 10
        for (i in 0 until 10) {
            val a = when {
                i < lit -> 1f
                i == lit -> blink
                else -> 0.18f
            }
            drawCircle(White.copy(alpha = a), 2.6.dp.toPx(), androidx.compose.ui.geometry.Offset(step * (i + 0.5f), size.height / 2))
        }
    }
}
