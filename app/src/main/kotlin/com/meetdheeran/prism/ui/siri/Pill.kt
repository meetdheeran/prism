package com.meetdheeran.prism.ui.siri

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetdheeran.prism.ai.Citation
import com.meetdheeran.prism.ui.glass.BackdropState
import com.meetdheeran.prism.ui.glass.GlassStyle
import com.meetdheeran.prism.ui.glass.LiquidGlass
import com.meetdheeran.prism.ui.motion.Motion
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography

/**
 * The bottom pill: type or talk. The mic button becomes a stop button with a level ring while
 * listening; typing swaps it for a send button. Glass over whatever the screen holds.
 */
@Composable
fun ListeningPill(
    backdrop: BackdropState,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onStop: () -> Unit,
    listening: Boolean,
    level: Float,
    processing: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask Prism…",
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val accent = LocalAccent.current
    LiquidGlass(backdrop, modifier.fillMaxWidth(), RoundedCornerShape(30.dp), GlassStyle.Regular) {
        Row(
            Modifier
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                .animateContentSize(Motion.panel()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) { leading(); Spacer(Modifier.width(2.dp)) } else Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (text.isEmpty()) {
                    Text(
                        if (listening) "Listening…" else if (processing) "Transcribing…" else placeholder,
                        style = PrismTypography.bodyLarge, color = PrismColors.TextTertiary,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = enabled && !listening,
                    textStyle = PrismTypography.bodyLarge.copy(color = PrismColors.TextPrimary),
                    cursorBrush = SolidColor(accent),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend() }),
                )
            }
            Spacer(Modifier.width(6.dp))
            when {
                listening -> RingButton(level = level, accent = accent, onClick = onStop) {
                    Icon(Icons.Filled.Stop, "Stop", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                text.isNotBlank() -> RingButton(level = 0f, accent = accent, filled = true, onClick = onSend) {
                    Icon(Icons.Rounded.ArrowUpward, "Send", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                else -> RingButton(level = 0f, accent = accent, onClick = onMic, enabled = enabled && !processing) {
                    Icon(Icons.Filled.Mic, "Talk", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun RingButton(level: Float, accent: Color, onClick: () -> Unit, filled: Boolean = false, enabled: Boolean = true, content: @Composable () -> Unit) {
    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), Motion.snappy(), label = "ring")
    Box(
        Modifier
            .size(44.dp)
            .pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(44.dp)) {
            val r = size.minDimension / 2f
            if (lvl > 0f) {
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f * lvl), Color.Transparent)), r * (1f + 0.6f * lvl))
                drawCircle(accent.copy(alpha = 0.9f), r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx() + 3.dp.toPx() * lvl))
            }
        }
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (filled) Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.75f)))
                    else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.08f))),
                ),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** The answer sheet: grows from the pill as text streams in; chips for tools and sources. */
@Composable
fun ResponseCard(
    backdrop: BackdropState,
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    citations: List<Citation> = emptyList(),
    chips: List<String> = emptyList(),
    error: String? = null,
    onOpenUrl: (String) -> Unit = {},
) {
    LiquidGlass(backdrop, modifier.fillMaxWidth(), RoundedCornerShape(26.dp), GlassStyle.Dark) {
        Column(Modifier.padding(18.dp).animateContentSize(Motion.panel())) {
            if (text.isEmpty() && streaming && error == null) {
                ThinkingDots(Modifier.padding(vertical = 6.dp))
            } else {
                Text(
                    text + if (streaming) " ▍" else "",
                    style = PrismTypography.bodyLarge.copy(lineHeight = 24.sp),
                    color = PrismColors.TextPrimary,
                )
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error, style = PrismTypography.bodyMedium, color = PrismColors.Bad)
            }
            if (chips.isNotEmpty() || citations.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    chips.forEach { Chip(it); Spacer(Modifier.width(6.dp)) }
                    citations.forEach { c -> Chip(c.title.ifBlank { c.url }, accent = true) { onOpenUrl(c.url) }; Spacer(Modifier.width(6.dp)) }
                }
            }
        }
    }
}

@Composable
fun Chip(label: String, accent: Boolean = false, onClick: (() -> Unit)? = null) {
    val a = LocalAccent.current
    val base = Modifier
        .clip(RoundedCornerShape(12.dp))
        .background(if (accent) a.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f))
        .padding(horizontal = 10.dp, vertical = 5.dp)
    Box(if (onClick != null) Modifier.pressable(onClick = onClick).then(base) else base) {
        Text(label.take(48), style = TextStyle(fontSize = 12.sp, color = if (accent) Color.White else PrismColors.TextSecondary))
    }
}
