package com.meetdheeran.prism.assistant

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.nothing.GlyphMatrix
import com.meetdheeran.prism.ui.nothing.Typewriter
import com.meetdheeran.prism.ui.siri.EdgeGlow
import com.meetdheeran.prism.ui.siri.Phase
import com.meetdheeran.prism.ui.theme.NothingFonts
import com.meetdheeran.prism.ui.theme.NothingPalette

private val Ink = Color.White
private val Dim = Color(0xFF8A8A8A)
private val Line = Color(0xFF2E2E2E)
private val Mono = TextStyle(fontFamily = NothingFonts.Mono, fontSize = 14.sp, lineHeight = 21.sp, color = Ink)
private val Label = TextStyle(fontFamily = NothingFonts.Mono, fontSize = 10.sp, letterSpacing = 1.2.sp, color = Dim)

/**
 * The assistant in the Nothing look: the island itself grows out of the camera notch into a black
 * panel — glyph matrix, status, what you said, and a mono readout of the answer typing itself out —
 * while glyph light strips pulse on the screen edges. Always black with white type, like the island,
 * whatever the phone's theme. The app underneath stays visible; tapping outside closes it.
 */
@Composable
fun NothingSessionUi(
    model: SessionModel,
    phase: Phase,
    level: Float,
    listening: Boolean,
    partial: String,
    speech: SpeechState,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val ctx = LocalContext.current
    val settings by model.settings
    var keyboard by remember { mutableStateOf(false) }
    // Grow out of the notch: start as a small pill at the top and spring open.
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f)) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
        EdgeGlow(active = true, level = level, phase = phase)

        val shape = RoundedCornerShape(30.dp)
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .imePadding()
                .padding(top = 4.dp, start = 10.dp, end = 10.dp)
                .graphicsLayer {
                    val g = grow.value
                    scaleX = 0.25f + 0.75f * g
                    scaleY = 0.12f + 0.88f * g
                    alpha = (g * 1.6f).coerceAtMost(1f)
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .fillMaxWidth()
                .background(Color.Black, shape)
                .border(1.dp, Line, shape)
                // Taps inside the panel must not fall through to the close scrim.
                .clickable(remember { MutableInteractionSource() }, null) {}
                .animateContentSize()
                .padding(start = 18.dp, end = 18.dp, top = 30.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphMatrix(level = level, phase = phase, size = 44.dp, grid = 13)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (listening) {
                            Canvas(Modifier.size(6.dp)) { drawCircle(NothingPalette.Red) }
                            Spacer(Modifier.width(6.dp))
                        }
                        val status = when {
                            speech is SpeechState.Processing -> "TRANSCRIBING"
                            phase == Phase.Listening -> "LISTENING"
                            phase == Phase.Thinking -> "THINKING"
                            phase == Phase.Speaking -> "SPEAKING"
                            else -> settings.assistantName.lowercase()
                        }
                        Text(status, style = Label)
                    }
                    val said = if (listening) partial else model.asked
                    if (said.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(said, style = TextStyle(fontFamily = NothingFonts.Grotesk, fontSize = 16.sp, lineHeight = 21.sp, color = Ink), maxLines = 3)
                    }
                }
            }

            val hasAnswer = model.response.isNotEmpty() || model.error != null || model.pending != null
            if (hasAnswer) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                Spacer(Modifier.height(12.dp))
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (model.response.isNotEmpty()) {
                        if (model.streaming) Typewriter(model.response, Mono) else Text(model.response, style = Mono)
                    }
                    model.error?.let { Text(it, style = Mono.copy(color = NothingPalette.Red)) }
                    model.pending?.let { p ->
                        Text("ALLOW: ${p.description}", style = Mono)
                        Spacer(Modifier.height(10.dp))
                        Row {
                            PanelChip("Don't") { model.confirm(false) }
                            Spacer(Modifier.width(8.dp))
                            PanelChip("Allow", red = true) { model.confirm(true) }
                        }
                    }
                }
                if (model.chips.isNotEmpty() || model.citations.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        model.chips.forEach { PanelChip(it); Spacer(Modifier.width(6.dp)) }
                        model.citations.forEach { c ->
                            PanelChip(c.title.ifBlank { c.url }, red = true) {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }
            (speech as? SpeechState.Error)?.let { e ->
                Spacer(Modifier.height(8.dp))
                Text(e.message, style = Mono.copy(color = NothingPalette.Red, fontSize = 12.sp))
            }

            if (keyboard) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(50)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (model.input.isEmpty()) Text("type_", style = Mono.copy(color = Dim))
                        BasicTextField(
                            model.input, { model.input = it }, textStyle = Mono, cursorBrush = SolidColor(NothingPalette.Red), maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { model.send(model.input) }),
                        )
                    }
                    PanelChip("Send", red = model.input.isNotBlank()) { model.send(model.input) }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelChip(if (listening) "Stop" else "Talk", red = listening) { if (listening) model.speech.stop() else model.listen() }
                PanelChip(if (keyboard) "Voice" else "Type") { keyboard = !keyboard }
                Spacer(Modifier.weight(1f))
                PanelChip("Open") { onOpenApp() }
            }
        }
    }
}

@Composable
private fun PanelChip(label: String, red: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    val base = Modifier
        .border(1.dp, if (red) NothingPalette.Red else Line, shape)
        .background(if (red) NothingPalette.Red else Color.Transparent, shape)
        .padding(horizontal = 12.dp, vertical = 7.dp)
    Box(if (onClick != null) Modifier.pressable(onClick = onClick).then(base) else base) {
        Text(label.take(40).uppercase(), style = Label.copy(color = Ink))
    }
}
