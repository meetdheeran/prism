package com.meetdheeran.prism.island

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.ln

/**
 * How loud the music really is, in four bands (bass → treble), for the island's bars: Android's audio visualiser on
 * the output mix — the sound that's playing, never the microphone (Android still gates it behind the microphone
 * permission, which Prism has for talking). It runs only while something holds it: bars on screen with music
 * playing. Without the permission, or if the phone refuses the visualiser, nothing is held and the bars keep their
 * own animation.
 */
internal object Beat {
    const val BANDS = 4

    /** 0…1 per band, smoothed: quick to rise, slower to fall, like a real meter. */
    val levels = FloatArray(BANDS)

    /** Bumped on every capture; reading it in a draw block redraws with each one. */
    var frame by mutableLongStateOf(0L)
        private set

    private var viz: Visualizer? = null
    private var holders = 0
    private val peaks = FloatArray(BANDS) { 1f }

    /** Upper edge of each band in Hz: bass, low mids, high mids, treble. */
    private val edges = floatArrayOf(160f, 700f, 2_800f, 11_000f)

    /** True when the bars can follow the music (pair it with [release]). */
    fun hold(ctx: Context): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (viz == null) start()
        if (viz == null) return false
        holders++
        return true
    }

    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) stop()
    }

    private fun start() {
        viz = runCatching {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            if (fft != null) take(fft, samplingRate)
                        }
                    },
                    (Visualizer.getMaxCaptureRate() * 2 / 3).coerceAtLeast(1),
                    false,
                    true,
                )
                enabled = true
            }
        }.getOrNull()
    }

    private fun stop() {
        runCatching {
            viz?.enabled = false
            viz?.release()
        }
        viz = null
        levels.fill(0f)
    }

    /**
     * [fft] holds Re/Im pairs for bins 1 until n/2 (bin k is k·rate/n Hz, [rate] in milliHertz). Each band's loudness
     * is its bins' average magnitude on a log scale, against a peak that slowly forgets, so quiet songs still move.
     */
    private fun take(fft: ByteArray, rate: Int) {
        val n = fft.size
        if (n < 8) return
        val hzPerBin = rate / 1000f / n
        val sum = FloatArray(BANDS)
        val count = IntArray(BANDS)
        var k = 1
        while (k < n / 2) {
            val hz = k * hzPerBin
            if (hz > edges.last()) break
            val band = edges.indexOfFirst { hz <= it }.coerceAtLeast(0)
            sum[band] += hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
            count[band]++
            k++
        }
        for (b in 0 until BANDS) {
            val energy = if (count[b] > 0) ln(1f + sum[b] / count[b]) else 0f
            peaks[b] = maxOf(peaks[b] * 0.995f, energy, 0.5f)
            val target = (energy / peaks[b]).coerceIn(0f, 1f)
            levels[b] = if (target > levels[b]) levels[b] + (target - levels[b]) * 0.65f else levels[b] * 0.82f + target * 0.18f
        }
        frame++
    }
}
