package com.meetdheeran.prism.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Records the microphone as 16 kHz mono PCM16 and hands back a WAV byte array — exactly what
 * Groq Whisper and Gemini audio accept without a codec. Instead of a fixed duration it does a
 * small energy-based VAD: it waits for speech, then stops ~1.2 s after the user goes quiet, so
 * a cloud round-trip starts the moment the sentence ends. Leading silence is trimmed to keep
 * uploads small and to stop Whisper hallucinating text for empty audio.
 *
 * Nothing here is ever written to disk; the bytes live only for the transcription call.
 */
class WavRecorder(context: Context) {

    /** What a finished capture contains. [hadSpeech] false means the user said nothing audible. */
    class Recording(val wav: ByteArray, val hadSpeech: Boolean, val durationMs: Long)

    /** RECORD_AUDIO is not granted; the UI should offer the permission prompt. */
    class MicPermissionMissing : Exception("Prism needs the microphone to listen")

    /** The mic exists but could not be opened (another app holds it, or the HAL refused). */
    class MicUnavailable(message: String) : Exception(message)

    /** [cancel] was called while capturing; nothing was kept. */
    class RecordingCancelled : Exception("Recording cancelled")

    private val ctx = context.applicationContext

    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Finish now and return what was captured so far (the user tapped the pill). */
    fun stop() { stopRequested = true }

    /** Abort and discard; [record] returns [RecordingCancelled]. */
    fun cancel() { cancelRequested = true }

    /**
     * Capture until speech ends, [stop] is called, [maxMs] elapses, or nobody speaks for
     * [noSpeechTimeoutMs]. [onLevel] is called ~50 times a second on the IO thread with a
     * smoothed 0..1 loudness for the listening pill.
     */
    suspend fun record(
        maxMs: Long = MAX_MS,
        silenceMs: Long = SILENCE_AFTER_SPEECH_MS,
        noSpeechTimeoutMs: Long = NO_SPEECH_TIMEOUT_MS,
        onLevel: (Float) -> Unit,
    ): Result<Recording> = withContext(Dispatchers.IO) {
        stopRequested = false
        cancelRequested = false
        if (!hasPermission()) return@withContext Result.failure(MicPermissionMissing())
        captureBlocking(maxMs, silenceMs, noSpeechTimeoutMs, onLevel) { isActive }
    }

    private fun captureBlocking(
        maxMs: Long,
        silenceMs: Long,
        noSpeechTimeoutMs: Long,
        onLevel: (Float) -> Unit,
        stillActive: () -> Boolean,
    ): Result<Recording> {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return Result.failure(MicUnavailable("This phone can't record 16 kHz audio"))

        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, max(minBuf, FRAME_BYTES * 8))
        } catch (e: Exception) {
            return Result.failure(MicUnavailable("Microphone is unavailable"))
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Result.failure(MicUnavailable("Microphone is unavailable"))
        }

        val pcm = ByteArrayOutputStream(SAMPLE_RATE * 2 * 8)
        val frame = ShortArray(FRAME_SAMPLES)
        val frameBytes = ByteArray(FRAME_BYTES)
        val start = SystemClock.elapsedRealtime()

        var level = 0f
        var noiseFloorDb = 0f
        var frameIndex = 0
        var speechRun = 0
        var speechStarted = false
        var onsetByte = 0
        var lastSpeechAt = start

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return Result.failure(MicUnavailable("Another app is using the microphone"))
            }
            while (true) {
                if (cancelRequested || !stillActive()) return Result.failure(RecordingCancelled())
                val n = record.read(frame, 0, frame.size)
                if (n < 0) return Result.failure(MicUnavailable("Microphone read failed"))
                if (n == 0) continue

                // Loudness of this 20 ms frame in dBFS (-90 silence .. 0 clipping).
                var sum = 0.0
                for (i in 0 until n) { val v = frame[i].toDouble(); sum += v * v }
                val rms = sqrt(sum / n)
                val db = (20.0 * log10(max(rms, 1.0) / 32768.0)).toFloat()

                // Visual level: fast attack, slower release, so bars feel alive but don't flicker.
                val target = ((db + 60f) / 50f).coerceIn(0f, 1f)
                level += (target - level) * if (target > level) 0.6f else 0.18f
                onLevel(level)

                // Adaptive noise floor: the first 160 ms set it, then it only creeps upward.
                noiseFloorDb = when {
                    frameIndex == 0 -> db
                    frameIndex < 8 -> minOf(noiseFloorDb, db)
                    db < noiseFloorDb -> db
                    else -> (noiseFloorDb + (db - noiseFloorDb) * 0.01f).coerceAtMost(-30f)
                }
                frameIndex++

                val now = SystemClock.elapsedRealtime()
                val isSpeech = db > noiseFloorDb + SPEECH_MARGIN_DB && db > ABSOLUTE_SPEECH_DB
                if (isSpeech) {
                    speechRun++
                    if (!speechStarted && speechRun >= ONSET_FRAMES) {
                        speechStarted = true
                        onsetByte = max(0, pcm.size() - ONSET_BACKTRACK_BYTES)
                    }
                    if (speechStarted) lastSpeechAt = now
                } else if (speechRun > 0) {
                    speechRun--
                }

                ByteBuffer.wrap(frameBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(frame, 0, n)
                pcm.write(frameBytes, 0, n * 2)

                if (stopRequested) break
                if (now - start >= maxMs) break
                if (speechStarted && now - lastSpeechAt >= silenceMs) break
                if (!speechStarted && now - start >= noSpeechTimeoutMs) break
            }
        } catch (e: IllegalStateException) {
            return Result.failure(MicUnavailable("Microphone stopped unexpectedly"))
        } finally {
            runCatching { record.stop() }
            record.release()
        }

        val all = pcm.toByteArray()
        val kept = if (speechStarted && onsetByte > 0) all.copyOfRange(onsetByte, all.size) else all
        val durationMs = kept.size.toLong() / BYTES_PER_MS
        return Result.success(Recording(wav = toWav(kept), hadSpeech = speechStarted, durationMs = durationMs))
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_MS = 30_000L
        const val SILENCE_AFTER_SPEECH_MS = 1_200L
        const val NO_SPEECH_TIMEOUT_MS = 7_000L

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SAMPLES = SAMPLE_RATE / 50           // 20 ms
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000       // 32
        private const val ONSET_FRAMES = 4                            // 80 ms of energy before we call it speech
        private const val ONSET_BACKTRACK_BYTES = 400 * BYTES_PER_MS  // keep 400 ms before the onset
        private const val SPEECH_MARGIN_DB = 9f
        private const val ABSOLUTE_SPEECH_DB = -50f

        /** Wrap raw PCM16 mono 16 kHz in a canonical 44-byte RIFF/WAVE header. */
        fun toWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            out.put("RIFF".toByteArray(Charsets.US_ASCII))
            out.putInt(36 + pcm.size)
            out.put("WAVE".toByteArray(Charsets.US_ASCII))
            out.put("fmt ".toByteArray(Charsets.US_ASCII))
            out.putInt(16)
            out.putShort(1)                       // PCM
            out.putShort(channels.toShort())
            out.putInt(sampleRate)
            out.putInt(byteRate)
            out.putShort(blockAlign.toShort())
            out.putShort(bitsPerSample.toShort())
            out.put("data".toByteArray(Charsets.US_ASCII))
            out.putInt(pcm.size)
            out.put(pcm)
            return out.array()
        }
    }
}

/**
 * Transient, duckable audio focus for the assistant. Music keeps playing quietly while Prism
 * listens or talks, and comes back to full volume the moment we abandon focus. Shared by
 * [SpeechInput] and [SpeechOutput] so both sides behave identically.
 */
internal class AssistantAudioFocus(context: Context) {
    private val am: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    @Synchronized
    fun request() {
        if (request != null) return
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { am?.requestAudioFocus(r) }
        request = r
    }

    @Synchronized
    fun abandon() {
        val r = request ?: return
        runCatching { am?.abandonAudioFocusRequest(r) }
        request = null
    }

    companion object {
        /** USAGE_ASSISTANT routes through the assistant stream, the same one Google Assistant uses. */
        val attributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
