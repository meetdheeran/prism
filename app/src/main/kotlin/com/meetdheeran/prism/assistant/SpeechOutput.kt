package com.meetdheeran.prism.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * The assistant's voice: Android's on-device TextToSpeech (Google engine when present).
 * Free, unlimited and private. Replies are stripped of markdown and split into sentence
 * chunks so long answers never hit the engine's per-utterance limit. Music ducks while we talk.
 */
class SpeechOutput(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: List<Pair<String, String>> = emptyList()
    private var lastId: String? = null
    private var counter = 0

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    /** Character range currently being spoken (start, end) within the chunk; for word highlighting. */
    private val _range = MutableStateFlow(0 to 0)
    val range: StateFlow<Pair<Int, Int>> = _range

    init {
        val engine = if (isInstalled("com.google.android.tts")) "com.google.android.tts" else null
        tts = TextToSpeech(ctx, { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                configureVoice()
                tts?.setOnUtteranceProgressListener(progress)
                if (queued.isNotEmpty()) { val q = queued; queued = emptyList(); q.forEach { speak(it.second, it.first) } }
            }
        }, engine)
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post { requestFocus(); _speaking.value = true }
        }

        override fun onDone(utteranceId: String?) {
            main.post { if (utteranceId == lastId) finish() }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { main.post { finish() } }

        override fun onError(utteranceId: String?, errorCode: Int) { main.post { finish() } }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            _range.value = start to end
        }
    }

    private fun finish() {
        _speaking.value = false
        abandonFocus()
    }

    private fun isInstalled(pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun configureVoice() {
        val t = tts ?: return
        runCatching {
            t.language = Locale.getDefault()
            // Prefer a network-free, high-quality voice for the user's language.
            val best = t.voices?.filter { it.locale.language == Locale.getDefault().language && !it.isNetworkConnectionRequired }
                ?.sortedByDescending { it.quality }?.firstOrNull()
            if (best != null) t.voice = best
            t.setSpeechRate(1.02f)
            t.setPitch(1.0f)
        }
    }

    fun voices(): List<Voice> = runCatching { tts?.voices?.toList() }.getOrNull().orEmpty()

    fun setVoice(name: String): Boolean {
        val v = voices().firstOrNull { it.name == name } ?: return false
        return tts?.setVoice(v) == TextToSpeech.SUCCESS
    }

    /** Speaks [text], replacing anything currently being spoken. */
    fun speak(text: String, id: String = "prism-${counter++}") {
        val clean = stripMarkdown(text).trim()
        if (clean.isEmpty()) return
        if (!ready) { queued = queued + (id to text); return }
        val t = tts ?: return
        val chunks = chunk(clean)
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        chunks.forEachIndexed { i, c ->
            val uid = "$id#$i"
            if (i == chunks.lastIndex) lastId = uid
            t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, uid)
        }
    }

    fun stop() {
        tts?.stop()
        finish()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .build()
        focus = req
        audio?.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
    }

    companion object {
        fun openTtsSettings(ctx: Context) {
            val i = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }

        /** Markdown reads badly aloud: drop emphasis, code ticks, headers, link targets, bullets. */
        fun stripMarkdown(s: String): String = s
            .replace(Regex("```[\\s\\S]*?```"), " code block ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
            .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
            .replace(Regex("\\*\\*|__|~~"), "")
            .replace(Regex("(?<!\\w)[*_](?=\\S)|(?<=\\S)[*_](?!\\w)"), "")
            .replace(Regex("【[^】]*】"), "")
            .replace(Regex("[ \\t]+"), " ")

        /** Sentence-aware chunks under the engine's limit. */
        fun chunk(s: String, max: Int = 3500): List<String> {
            if (s.length <= max) return listOf(s)
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (sentence in s.split(Regex("(?<=[.!?\\n])\\s+"))) {
                if (sb.length + sentence.length + 1 > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                if (sentence.length > max) { sentence.chunked(max).forEach { out += it }; continue }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(sentence)
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }
    }
}

/** Apple-style haptics on top of Android's predefined effects. */
object Haptics {
    private fun vibrator(ctx: Context): Vibrator? =
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator

    private fun play(ctx: Context, effect: Int) {
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    fun tick(ctx: Context) = play(ctx, VibrationEffect.EFFECT_TICK)
    fun click(ctx: Context) = play(ctx, VibrationEffect.EFFECT_CLICK)
    fun heavy(ctx: Context) = play(ctx, VibrationEffect.EFFECT_HEAVY_CLICK)
    fun success(ctx: Context) = play(ctx, VibrationEffect.EFFECT_DOUBLE_CLICK)
}
