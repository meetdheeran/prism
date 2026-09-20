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
    var structureText: String? = null
    var conversationId: Long? = null
        private set
    val speech = SpeechInput(ctx)
    private var job: Job? = null
    private var screenshotUsed = false

    fun begin(autoListen: Boolean) {
        response = ""; error = null; chips = emptyList(); citations = emptyList(); input = ""; streaming = false
        screenshot = null; structureText = null; screenshotUsed = false; conversationId = null
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
        speech.start(s.voiceInput, onFinal = { if (it.isNotBlank()) send(it) }, transcribe = transcriber)
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
                    is EngineEvent.Done -> { conversationId = ev.conversationId; streaming = false; if (settings.value.speakReplies) graph.speechOutput.speak(ev.fullText) }
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
    val speaking by AppGraph.get(androidx.compose.ui.platform.LocalContext.current).speechOutput.speaking.collectAsState()
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
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Box(Modifier.fillMaxSize()) {
        // Dim the app underneath just enough for the glass to read; tap to dismiss.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
        // Invisible source so the pill/card glass has a backdrop to sample (the app behind is not capturable live).
        Box(Modifier.fillMaxSize()) { GlassBackground(backdrop, accent = Color(settings.accentArgb), animated = false, intensity = 0.35f, opaque = false) }
        EdgeGlow(active = true, level = level, phase = phase)

        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
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
                Text(e.message, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            ListeningPill(
                backdrop = backdrop,
                text = if (listening) (speech as SpeechState.Listening).partial.ifEmpty { model.input } else model.input,
                onTextChange = { model.input = it },
                onSend = { model.send(model.input) },
                onMic = { model.listen() },
                onStop = { model.speech.stop() },
                listening = listening,
                level = level,
                processing = speech is SpeechState.Processing,
                placeholder = "Ask ${settings.assistantName}…",
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Chip("Open ${settings.assistantName}") { onOpenApp() }
            }
        }
    }
}
