package com.meetdheeran.prism.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meetdheeran.prism.ai.Attachment
import com.meetdheeran.prism.ai.Citation
import com.meetdheeran.prism.ai.EngineEvent
import com.meetdheeran.prism.ai.Providers
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Settings
import com.meetdheeran.prism.core.VoiceInputMode
import com.meetdheeran.prism.shizuku.ShizukuBridge
import com.meetdheeran.prism.ui.glass.rememberBackdropState
import com.meetdheeran.prism.ui.glass.GlassBackground
import com.meetdheeran.prism.ui.motion.Motion
import com.meetdheeran.prism.ui.siri.Chip
import com.meetdheeran.prism.ui.siri.EdgeGlow
import com.meetdheeran.prism.ui.siri.ListeningPill
import com.meetdheeran.prism.ui.siri.Phase
import com.meetdheeran.prism.ui.siri.ResponseCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.meetdheeran.prism.ui.glass.LensRegistry
import com.meetdheeran.prism.ui.glass.LocalLens
import com.meetdheeran.prism.ui.glass.RefractionSurface
import com.meetdheeran.prism.ui.glass.lens
import com.meetdheeran.prism.ui.motion.LocalTilt
import kotlinx.coroutines.delay
import com.meetdheeran.prism.core.AssistantStyle
import com.meetdheeran.prism.ui.glass.GlassStyle
import com.meetdheeran.prism.ui.glass.backdropSource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.coroutines.launch

/** State for one assist session (no ViewModel: sessions live in a service, not an Activity). */
class SessionModel(private val ctx: Context, private val graph: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = mutableStateOf(Settings())
    var input by mutableStateOf("")
    var response by mutableStateOf("")
    var streaming by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var chips by mutableStateOf<List<String>>(emptyList())
    var citations by mutableStateOf<List<Citation>>(emptyList())
    var pending by mutableStateOf<EngineEvent.NeedsConfirmation?>(null)
    var screenshot: ByteArray? = null
    /** Half-size copy of the assist screenshot, the lens bubble's backdrop. */
    var screenBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    var structureText: String? = null
    var conversationId: Long? = null
        private set
    val speech = SpeechInput(ctx)
    private var job: Job? = null
    private var screenshotUsed = false
    var onEnd: (() -> Unit)? = null
    private var awaitListen = false

    init {
        scope.launch { graph.speechOutput.speaking.collect { sp -> if (!sp && awaitListen) { awaitListen = false; delay(300); listen() } } }
    }

    fun begin(autoListen: Boolean) {
        response = ""; error = null; chips = emptyList(); citations = emptyList(); input = ""; streaming = false
        screenshot = null; screenBitmap = null; structureText = null; screenshotUsed = false; conversationId = null
        scope.launch {
            settings.value = graph.prefs.settings.first()
            if (autoListen && !graph.assistant.isConfigured()) {
                error = Providers.missingKeyMessage(settings.value.provider)
                return@launch
            }
            if (autoListen) listen()
        }
    }

    fun end() {
        awaitListen = false
        speech.cancel()
        job?.cancel()
        graph.speechOutput.stop()
    }

    fun listen() {
        graph.speechOutput.stop()
        val s = settings.value
        val transcriber: (suspend (ByteArray) -> Result<String>?)? = if (s.voiceInput == VoiceInputMode.SYSTEM) null else { wav ->
            val p = if (s.voiceInput == VoiceInputMode.GROQ_WHISPER) Providers.groq() else Providers.gemini()
            val key = Providers.apiKey(ctx, p.provider)
            if (key == null) Result.failure(IllegalStateException(Providers.missingKeyMessage(p.provider))) else p.transcribe(key, wav)
        }
        speech.start(s.voiceInput, onFinal = { if (isEndPhrase(it)) onEnd?.invoke() else if (it.isNotBlank()) send(it) }, transcribe = transcriber)
    }

    private val screenWords = Regex("\\b(screen|this|here|see|look|page|photo|image|picture|read|what'?s on|showing|displayed|text)\\b", RegexOption.IGNORE_CASE)

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        input = ""
        job?.cancel()
        response = ""; error = null; chips = emptyList(); citations = emptyList(); streaming = true
        val wantsScreen = screenWords.containsMatchIn(t)
        job = scope.launch {
            val atts = ArrayList<Attachment>()
            var prompt = t
            if (wantsScreen && !screenshotUsed) {
                val shot = screenshot ?: if (ShizukuBridge.isReady()) ShizukuBridge.screenshotPng().getOrNull()?.let { com.meetdheeran.prism.core.Images.toJpeg(it) } else null
                if (shot != null) { atts += Attachment("image/jpeg", shot, "screenshot"); screenshotUsed = true }
                structureText?.takeIf { it.isNotBlank() }?.let { prompt = "$t\n\n[Text currently visible on screen]\n${it.take(2500)}" }
            }
            graph.assistant.send(conversationId, prompt, atts).collect { ev ->
                when (ev) {
                    EngineEvent.Thinking -> Unit
                    is EngineEvent.Text -> response += ev.delta
                    is EngineEvent.ToolRunning -> chips = chips + "${ev.label}…"
                    is EngineEvent.ToolDone -> chips = chips.toMutableList().also { l -> val i = l.indexOfLast { it.endsWith("…") }; val v = ev.userVisible ?: ev.name; if (i >= 0) l[i] = v else l += v }
                    is EngineEvent.NeedsConfirmation -> pending = ev
                    is EngineEvent.Citations -> citations = ev.items
                    is EngineEvent.Remembered -> chips = chips + "Saved to memory"
                    is EngineEvent.Done -> {
                        conversationId = ev.conversationId; streaming = false
                        val speak = settings.value.speakReplies && ev.fullText.isNotBlank()
                        if (speak) graph.speechOutput.speak(ev.fullText)
                        if (settings.value.conversationMode) { if (speak) awaitListen = true else { delay(300); listen() } }
                    }
                    is EngineEvent.Error -> { error = ev.message; streaming = false }
                }
            }
        }
    }

    fun confirm(accept: Boolean) { pending = null; graph.assistant.confirm(accept) }
}

@Composable
fun SessionUi(model: SessionModel, onClose: () -> Unit, onOpenApp: () -> Unit) {
    val backdrop = rememberBackdropState()
    val speech by model.speech.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val speaking by AppGraph.get(ctx).speechOutput.speaking.collectAsState()
    val listening = speech is SpeechState.Listening
    val level = (speech as? SpeechState.Listening)?.level ?: 0f
    val phase = when {
        listening -> Phase.Listening
        model.streaming && model.response.isEmpty() -> Phase.Thinking
        speaking -> Phase.Speaking
        else -> Phase.Idle
    }
    val settingsState: State<Settings> = model.settings
    val settings by settingsState
    val edgeOnly = settings.assistantStyle != AssistantStyle.GLASS
    val lensMode = settings.assistantStyle == AssistantStyle.LENS
    var keyboard by remember { mutableStateOf(false) }
    val partial = (speech as? SpeechState.Listening)?.partial.orEmpty()
    val cardStyle = if (edgeOnly) GlassStyle.Dark.copy(tintAlpha = 0.62f) else GlassStyle.Dark

    Box(Modifier.fillMaxSize()) {
        // iOS-27 style keeps the app readable: barely any dimming, no sheet, just the ring of light.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (edgeOnly) 0.06f else 0.25f)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
        if (edgeOnly) Box(Modifier.fillMaxSize().backdropSource(backdrop))
        else Box(Modifier.fillMaxSize()) { GlassBackground(backdrop, accent = Color(settings.accentArgb), animated = false, intensity = 0.35f, opaque = false) }
        EdgeGlow(active = true, level = level, phase = phase)
        if (lensMode) LensBubble(model.screenBitmap, level, phase, Modifier.align(Alignment.TopCenter).padding(top = 2.dp))

        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.Bottom) {
            AnimatedVisibility(model.response.isNotEmpty() || model.streaming || model.error != null, enter = fadeIn(Motion.fade()) + slideInVertically(Motion.panel()) { it / 3 }, exit = fadeOut(Motion.fade())) {
                Column {
                    ResponseCard(backdrop, model.response, model.streaming, citations = model.citations, chips = model.chips, error = model.error) { url ->
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            model.pending?.let { p ->
                ResponseCard(backdrop, "Allow: ${p.description}", streaming = false)
                Spacer(Modifier.height(6.dp))
                Row {
                    Chip("Don't") { model.confirm(false) }
                    Spacer(Modifier.width(8.dp))
                    Chip("Allow", accent = true) { model.confirm(true) }
                }
                Spacer(Modifier.height(8.dp))
            }
            (speech as? SpeechState.Error)?.let { e ->
                Text(e.message, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            if (edgeOnly && !keyboard) {
                // Transcript floats over the app; no pill until the keyboard is asked for.
                val line = when {
                    listening -> partial.ifEmpty { "Listening\u2026" }
                    phase == Phase.Thinking -> "Thinking\u2026"
                    else -> ""
                }
                if (line.isNotEmpty()) {
                    Text(line, style = PrismTypography.titleMedium, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Chip(if (listening) "Stop" else "Talk", accent = listening) { if (listening) model.speech.stop() else model.listen() }
                    Spacer(Modifier.width(8.dp))
                    Chip("Keyboard") { keyboard = true }
                    Spacer(Modifier.width(8.dp))
                    Chip("Open ${settings.assistantName}") { onOpenApp() }
                }
            } else {
                ListeningPill(
                    backdrop = backdrop,
                    text = if (listening) partial.ifEmpty { model.input } else model.input,
                    onTextChange = { model.input = it },
                    onSend = { model.send(model.input) },
                    onMic = { model.listen() },
                    onStop = { model.speech.stop() },
                    listening = listening,
                    level = level,
                    processing = speech is SpeechState.Processing,
                    placeholder = "Ask ${settings.assistantName}\u2026",
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Chip("Open ${settings.assistantName}") { onOpenApp() }
                    if (edgeOnly) { Spacer(Modifier.width(8.dp)); Chip("Hide keyboard") { keyboard = false } }
                }
            }
        }
    }
}


/**
 * The iOS-27 Siri bubble: a clear lens over the camera that magnifies the screen underneath
 * (the assist screenshot Android hands the default assistant), swelling with the voice.
 */
@Composable
private fun LensBubble(bitmap: android.graphics.Bitmap?, level: Float, phase: Phase, modifier: Modifier) {
    val reg = remember { LensRegistry() }
    val fallback = remember { lensTint() }
    val target = 1f + 0.14f * level.coerceIn(0f, 1f) + (if (phase == Phase.Thinking) 0.05f else 0f)
    val scale by animateFloatAsState(target, Motion.pop(), label = "bubble")
    val shape = RoundedCornerShape(50)
    val radiusPx = with(LocalDensity.current) { 34.dp.toPx() }
    CompositionLocalProvider(LocalLens provides reg) {
        Box(
            modifier
                .size(width = 140.dp, height = 66.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.2f) }
                .clip(shape),
        ) {
            RefractionSurface(
                bitmap = bitmap ?: fallback, registry = reg, tilt = LocalTilt.current, reveal = 1f, dim = 0f,
                modifier = Modifier.fillMaxSize().lens("siri", radiusPx),
                textureIsSelf = bitmap == null,
                magnify = 1.3f, streak = 1f, clarity = if (bitmap != null) 0.85f else 0.25f, outsideAlpha = 0f,
            )
            Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.6f), shape))
        }
    }
}

private fun lensTint(): android.graphics.Bitmap {
    val w = 140; val h = 66
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF223066.toInt(), 0xFF0B1020.toInt(), 0xFF3A1A55.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
    android.graphics.Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    return out
}
