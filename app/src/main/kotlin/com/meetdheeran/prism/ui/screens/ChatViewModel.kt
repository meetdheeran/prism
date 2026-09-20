package com.meetdheeran.prism.ui.screens

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meetdheeran.prism.ai.Attachment
import com.meetdheeran.prism.ai.Citation
import com.meetdheeran.prism.ai.EngineEvent
import com.meetdheeran.prism.ai.Providers
import com.meetdheeran.prism.assistant.SpeechInput
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Images
import com.meetdheeran.prism.core.Settings
import com.meetdheeran.prism.core.VoiceInputMode
import com.meetdheeran.prism.data.MessageEntity
import com.meetdheeran.prism.assistant.ScreenCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The assistant's reply while it streams; becomes a persisted message on Done. */
data class Draft(
    val text: String = "",
    val thinking: Boolean = true,
    val chips: List<String> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = AppGraph.get(app)
    val settings: StateFlow<Settings> = graph.prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _conversationId = MutableStateFlow<Long?>(null)
    val conversationId: StateFlow<Long?> = _conversationId
    val messages: StateFlow<List<MessageEntity>> = _conversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else graph.history.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val conversations = graph.history.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var input by mutableStateOf("")
    val attachments = mutableStateListOf<Attachment>()
    var draft by mutableStateOf<Draft?>(null)
        private set
    var pendingConfirmation by mutableStateOf<EngineEvent.NeedsConfirmation?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    val speech = SpeechInput(app)
    val speaking get() = graph.speechOutput.speaking

    private var job: Job? = null
    val busy: Boolean get() = draft != null && draft?.error == null

    fun send(textOverride: String? = null) {
        val text = (textOverride ?: input).trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        val atts = attachments.toList()
        attachments.clear()
        input = ""
        job?.cancel()
        graph.speechOutput.stop()
        draft = Draft()
        job = viewModelScope.launch {
            graph.assistant.send(_conversationId.value, text.ifEmpty { "Describe what you see." }, atts).collect { ev ->
                val d = draft ?: Draft()
                when (ev) {
                    EngineEvent.Thinking -> draft = d.copy(thinking = true)
                    is EngineEvent.Text -> draft = d.copy(text = d.text + ev.delta, thinking = false)
                    is EngineEvent.ToolRunning -> draft = d.copy(chips = d.chips + "${ev.label}…", thinking = false)
                    is EngineEvent.ToolDone -> {
                        val chips = d.chips.toMutableList()
                        val i = chips.indexOfLast { it.endsWith("…") }
                        val label = ev.userVisible ?: ev.name.replace('_', ' ')
                        if (i >= 0) chips[i] = label else chips += label
                        draft = d.copy(chips = chips)
                    }
                    is EngineEvent.NeedsConfirmation -> pendingConfirmation = ev
                    is EngineEvent.Citations -> draft = d.copy(citations = ev.items)
                    is EngineEvent.Remembered -> draft = d.copy(chips = d.chips + "Saved to memory")
                    is EngineEvent.Done -> {
                        _conversationId.value = ev.conversationId
                        if (settings.value.speakReplies && ev.fullText.isNotBlank()) graph.speechOutput.speak(ev.fullText)
                        draft = null
                    }
                    is EngineEvent.Error -> {
                        ev.conversationId?.let { _conversationId.value = it }
                        draft = d.copy(error = ev.message, thinking = false)
                    }
                }
            }
        }
    }

    fun confirm(accept: Boolean) {
        pendingConfirmation = null
        graph.assistant.confirm(accept)
    }

    fun cancel() {
        job?.cancel()
        graph.assistant.cancel()
        graph.speechOutput.stop()
        draft = null
        pendingConfirmation = null
    }

    fun dismissError() { if (draft?.error != null) draft = null }

    fun startVoice() {
        graph.speechOutput.stop()
        val s = settings.value
        val transcriber: (suspend (ByteArray) -> Result<String>?)? = if (s.voiceInput == VoiceInputMode.SYSTEM) null else { wav ->
            val provider = if (s.voiceInput == VoiceInputMode.GROQ_WHISPER) Providers.groq() else Providers.gemini()
            val key = Providers.apiKey(getApplication(), provider.provider)
            if (key == null) Result.failure(IllegalStateException(Providers.missingKeyMessage(provider.provider)))
            else provider.transcribe(key, wav)
        }
        speech.start(s.voiceInput, onFinal = { text -> if (text.isNotBlank()) send(text) }, transcribe = transcriber)
    }

    fun stopVoice() = speech.stop()
    fun stopSpeaking() = graph.speechOutput.stop()

    fun captureScreen() {
        viewModelScope.launch {
            ScreenCapture.behindPrism(getApplication())
                .onSuccess { jpeg -> attachments += Attachment("image/jpeg", jpeg, "screenshot"); toast = "Screen captured (not stored)" }
                .onFailure { toast = "Can't capture: ${it.message}" }
        }
    }

    fun addUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val att = when {
                mime.startsWith("image/") -> Images.fromUri(app, uri)?.let { Attachment("image/jpeg", it, name) }
                mime == "application/pdf" || mime.startsWith("text/") ->
                    runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                        ?.takeIf { it.size <= 18 * 1024 * 1024 }?.let { Attachment(mime, it, name) }
                else -> null
            }
            withContext(Dispatchers.Main) {
                if (att != null) attachments += att else toast = "Unsupported or too large: $mime"
            }
        }
    }

    fun newChat() { cancel(); _conversationId.value = null }
    fun open(id: Long) { cancel(); _conversationId.value = id }
    fun rename(id: Long, title: String) { viewModelScope.launch { graph.history.rename(id, title) } }
    fun delete(id: Long) { viewModelScope.launch { graph.history.delete(id); if (_conversationId.value == id) newChat() } }
    fun clearAll() { viewModelScope.launch { graph.history.deleteAll(); newChat() } }

    override fun onCleared() {
        speech.release()
        super.onCleared()
    }
}
