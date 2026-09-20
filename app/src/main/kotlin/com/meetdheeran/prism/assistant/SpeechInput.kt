package com.meetdheeran.prism.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.meetdheeran.prism.core.VoiceInputMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** What the listening pill shows. One value at a time; [Result] and [Error] are terminal until the next [SpeechInput.start]. */
sealed interface SpeechState {
    data object Idle : SpeechState
    /** Mic is open. [level] is smoothed 0..1 loudness; [partial] is the live transcript (system mode only). */
    data class Listening(val level: Float, val partial: String) : SpeechState
    /** Audio captured, waiting for the recogniser or the cloud transcription. */
    data object Processing : SpeechState
    data class Result(val text: String) : SpeechState
    /** Friendly, user-facing message plus the action that would fix it (never a fake reply). */
    data class Error(val message: String, val fix: SpeechFix = SpeechFix.NONE) : SpeechState
}

/** The one-tap remedy the UI can offer next to a [SpeechState.Error]. */
enum class SpeechFix {
    NONE,
    /** Show the RECORD_AUDIO runtime prompt. */
    GRANT_MIC,
    /** No RecognitionService on the phone: point at Play Store / Google app. */
    INSTALL_SPEECH_SERVICE,
    /** The chosen cloud transcriber has no key: open the Keys screen. */
    ADD_API_KEY,
    /** Transient: just try again. */
    RETRY,
}

/**
 * Turns a 16 kHz mono WAV into text. `null` means the provider has no speech-to-text at all
 * (mirrors `AiProvider.transcribe`), so the caller can pass `{ wav -> provider.transcribe(key, wav) }`.
 */
typealias Transcriber = suspend (wav: ByteArray) -> Result<String>?

/**
 * Voice capture for the assistant. Two very different back-ends behind one tiny state machine:
 *
 *  - [VoiceInputMode.SYSTEM]: `android.speech.SpeechRecognizer` (the Google app on the OnePlus 7).
 *    Free, streams partials, needs no key. It is a main-thread API, so every call is bounced there.
 *  - [VoiceInputMode.GROQ_WHISPER] / [VoiceInputMode.GEMINI_AUDIO]: [WavRecorder] captures until
 *    the user stops talking, then the caller-supplied [Transcriber] does the network work. This
 *    class deliberately knows nothing about providers or keys.
 *
 * Audio focus is held (transient, may-duck) only while the mic is open.
 */
class SpeechInput(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val focus = AssistantAudioFocus(ctx)
    private val recorder = WavRecorder(ctx)

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var activeMode: VoiceInputMode? = null
    private var cloudJob: Job? = null
    /** Bumped on every start/cancel so late callbacks from a previous attempt are ignored. */
    private var session = 0
    private var level = 0f
    private var lastPartial = ""

    val isListening: Boolean get() = _state.value is SpeechState.Listening || _state.value is SpeechState.Processing

    fun hasMicPermission(): Boolean = recorder.hasPermission()

    /** True when a RecognitionService is installed (needs the `<queries>` entry in the manifest). */
    fun isSystemRecognitionAvailable(): Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)

    /**
     * Open the mic. [onFinal] fires once with the transcript (on the main thread); errors are
     * reported through [state] only. [transcribe] is required for the cloud modes and ignored for SYSTEM.
     */
    fun start(mode: VoiceInputMode, onFinal: (String) -> Unit, transcribe: Transcriber? = null) = onMain {
        cancelInternal()
        val id = ++session
        activeMode = mode
        if (!hasMicPermission()) {
            fail("Prism needs the microphone to listen", SpeechFix.GRANT_MIC)
            return@onMain
        }
        when (mode) {
            VoiceInputMode.SYSTEM -> startSystem(id, onFinal)
            VoiceInputMode.GROQ_WHISPER, VoiceInputMode.GEMINI_AUDIO -> startCloud(id, mode, onFinal, transcribe)
        }
    }

    /** The user tapped the pill: finish now with whatever was heard. */
    fun stop() = onMain {
        when (activeMode) {
            VoiceInputMode.SYSTEM -> {
                if (_state.value is SpeechState.Listening) {
                    _state.value = SpeechState.Processing
                    runCatching { recognizer?.stopListening() }
                }
            }
            VoiceInputMode.GROQ_WHISPER, VoiceInputMode.GEMINI_AUDIO -> recorder.stop()
            null -> Unit
        }
    }

    /** Abort and go back to [SpeechState.Idle]; nothing is delivered. */
    fun cancel() = onMain { cancelInternal(); _state.value = SpeechState.Idle }

    /** Release the recogniser and coroutines; call from the owning service/session's onDestroy. */
    fun release() = onMain {
        cancelInternal()
        recognizer?.destroy()
        recognizer = null
        scope.cancel()
    }

    // ---- system recogniser ----------------------------------------------------------------

    private fun startSystem(id: Int, onFinal: (String) -> Unit) {
        if (!isSystemRecognitionAvailable()) {
            fail("No speech recognition service on this phone", SpeechFix.INSTALL_SPEECH_SERVICE)
            return
        }
        val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull()
        if (r == null) {
            fail("Couldn't start the speech recogniser", SpeechFix.RETRY)
            return
        }
        recognizer = r
        r.setRecognitionListener(SystemListener(id, onFinal))
        level = 0f
        lastPartial = ""
        _state.value = SpeechState.Listening(0f, "")
        focus.request()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Match the cloud modes' feel: stop about a second after the sentence ends.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, WavRecorder.SILENCE_AFTER_SPEECH_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, WavRecorder.SILENCE_AFTER_SPEECH_MS)
        }
        runCatching { r.startListening(intent) }.onFailure {
            fail("Couldn't start the speech recogniser", SpeechFix.RETRY)
        }
    }

    private inner class SystemListener(private val id: Int, private val onFinal: (String) -> Unit) : RecognitionListener {
        private fun stale() = id != session

        override fun onReadyForSpeech(params: Bundle?) {
            if (stale()) return
            _state.value = SpeechState.Listening(level, lastPartial)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (stale()) return
            // The recogniser reports roughly -2..10 dB; map to 0..1 with a quick attack and slow release.
            val target = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            level += (target - level) * if (target > level) 0.55f else 0.2f
            if (_state.value is SpeechState.Listening) _state.value = SpeechState.Listening(level, lastPartial)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (stale()) return
            _state.value = SpeechState.Processing
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (stale()) return
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() } ?: return
            lastPartial = p
            if (_state.value is SpeechState.Listening) _state.value = SpeechState.Listening(level, p)
        }

        override fun onResults(results: Bundle?) {
            if (stale()) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: lastPartial.trim()
            if (text.isEmpty()) fail("Didn't catch that", SpeechFix.RETRY) else deliver(text, onFinal)
        }

        override fun onError(error: Int) {
            if (stale()) return
            // Some engines report NO_MATCH after stopListening() even though partials arrived; keep them.
            val salvage = lastPartial.trim()
            if (salvage.isNotEmpty() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                deliver(salvage, onFinal)
                return
            }
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                // The recogniser object is wedged; drop it so the next start() builds a fresh one.
                recognizer?.destroy()
                recognizer = null
            }
            val (message, fix) = friendlySystemError(error)
            fail(message, fix)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun friendlySystemError(error: Int): Pair<String, SpeechFix> = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that" to SpeechFix.RETRY
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything" to SpeechFix.RETRY
        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem — is another app using it?" to SpeechFix.RETRY
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Prism needs the microphone to listen" to SpeechFix.GRANT_MIC
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs a connection right now" to SpeechFix.RETRY
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recogniser is busy — try again" to SpeechFix.RETRY
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "The speech service had a problem" to SpeechFix.RETRY
        SpeechRecognizer.ERROR_CLIENT -> "Speech recogniser error — try again" to SpeechFix.RETRY
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests to the speech service — wait a moment" to SpeechFix.RETRY
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Your language isn't supported by the speech service" to SpeechFix.INSTALL_SPEECH_SERVICE
        else -> "Speech recognition failed" to SpeechFix.RETRY
    }

    // ---- cloud modes ------------------------------------------------------------------------

    private fun startCloud(id: Int, mode: VoiceInputMode, onFinal: (String) -> Unit, transcribe: Transcriber?) {
        if (transcribe == null) {
            fail("${mode.label} needs an API key — add one in Settings", SpeechFix.ADD_API_KEY)
            return
        }
        _state.value = SpeechState.Listening(0f, "")
        focus.request()
        cloudJob = scope.launch {
            val captured = recorder.record(onLevel = { l ->
                if (id == session) {
                    val s = _state.value
                    if (s is SpeechState.Listening) _state.value = SpeechState.Listening(l, s.partial)
                }
            })
            if (id != session) return@launch
            focus.abandon()

            val recording = captured.getOrElse { e ->
                when (e) {
                    is WavRecorder.RecordingCancelled -> _state.value = SpeechState.Idle
                    is WavRecorder.MicPermissionMissing -> fail(e.message ?: "Microphone permission needed", SpeechFix.GRANT_MIC)
                    else -> fail(e.message ?: "Microphone problem", SpeechFix.RETRY)
                }
                return@launch
            }
            if (!recording.hadSpeech) {
                fail("Didn't catch that", SpeechFix.RETRY)
                return@launch
            }

            _state.value = SpeechState.Processing
            val outcome: Result<String>? = try {
                transcribe(recording.wav)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (id != session) return@launch

            when {
                outcome == null -> fail("${mode.label} can't transcribe audio — pick another voice input in Settings", SpeechFix.NONE)
                outcome.isFailure -> fail(friendlyCloudError(outcome.exceptionOrNull()), SpeechFix.RETRY)
                else -> {
                    val text = outcome.getOrThrow().trim()
                    if (text.isEmpty()) fail("Didn't catch that", SpeechFix.RETRY) else deliver(text, onFinal)
                }
            }
        }
    }

    private fun friendlyCloudError(e: Throwable?): String {
        val m = e?.message?.trim().orEmpty()
        return if (m.isEmpty()) "Transcription failed — try again" else "Transcription failed: $m"
    }

    // ---- shared -----------------------------------------------------------------------------

    private fun deliver(text: String, onFinal: (String) -> Unit) {
        focus.abandon()
        activeMode = null
        _state.value = SpeechState.Result(text)
        onFinal(text)
    }

    private fun fail(message: String, fix: SpeechFix) {
        focus.abandon()
        activeMode = null
        _state.value = SpeechState.Error(message, fix)
    }

    /** Stops whatever is running without touching [state]; callers set the state they want next. */
    private fun cancelInternal() {
        session++
        cloudJob?.cancel()
        cloudJob = null
        recorder.cancel()
        runCatching { recognizer?.cancel() }
        focus.abandon()
        activeMode = null
        level = 0f
        lastPartial = ""
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
