/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.dj

import android.media.audiofx.Visualizer
import android.os.SystemClock
import timber.log.Timber
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Audio levels for the DJ screen. When a [Visualizer] is attached to the player's audio
 * session the levels follow the real FFT; otherwise a tempo-based pulse keeps the lights
 * alive. The Visualizer only reads the output — it never touches the audio itself.
 */
internal class DjAudioLevels {
    val bands = FloatArray(BAND_COUNT)
    var bass = 0f
        private set
    var energy = 0f
        private set

    private val target = FloatArray(BAND_COUNT)

    @Volatile
    private var lastCaptureAt = 0L
    private var clock = 0f

    val isLive: Boolean
        get() = SystemClock.elapsedRealtime() - lastCaptureAt < LIVE_TIMEOUT_MS

    fun attach(sessionId: Int): Visualizer? =
        try {
            Visualizer(sessionId).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) = Unit

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                            if (fft != null) onFft(fft)
                        }
                    },
                    (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(1),
                    false,
                    true,
                )
                enabled = true
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Visualizer unavailable for session $sessionId")
            null
        }

    fun detach(visualizer: Visualizer?) {
        if (visualizer == null) return
        try {
            visualizer.enabled = false
        } catch (_: Throwable) {
        }
        visualizer.release()
    }

    private fun onFft(fft: ByteArray) {
        val bins = fft.size / 2
        if (bins < 4) return
        // Log-spaced bands from bin 1 up to Nyquist.
        for (b in 0 until BAND_COUNT) {
            val from = (bins.toDouble().pow(b.toDouble() / BAND_COUNT)).toInt().coerceIn(1, bins - 1)
            val to = (bins.toDouble().pow((b + 1).toDouble() / BAND_COUNT)).toInt().coerceIn(from + 1, bins)
            var peak = 0f
            for (k in from until to) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                val magnitude = hypot(re, im)
                if (magnitude > peak) peak = magnitude
            }
            val db = if (peak > 0f) 20f * log10(peak) else 0f
            target[b] = ((db - 6f) / 36f).coerceIn(0f, 1f)
        }
        lastCaptureAt = SystemClock.elapsedRealtime()
    }

    /** Advances smoothing by [dt] seconds. Called once per frame from the UI thread. */
    fun step(dt: Float, isPlaying: Boolean) {
        clock += dt
        val live = isLive && isPlaying
        if (!live) synthesize(isPlaying)

        val attack = 1f - exp(-dt * 30f)
        val release = 1f - exp(-dt * 6f)
        var sum = 0f
        for (i in 0 until BAND_COUNT) {
            val goal = if (isPlaying) target[i] else 0f
            val rate = if (goal > bands[i]) attack else release
            bands[i] += (goal - bands[i]) * rate
            sum += bands[i]
        }
        energy = sum / BAND_COUNT
        bass = (bands[0] + bands[1] + bands[2] + bands[3]) / 4f
    }

    private fun synthesize(isPlaying: Boolean) {
        if (!isPlaying) return
        val beat = clock * FALLBACK_BPM / 60f
        val kick = exp(-(beat - beat.toInt()) * 7f)
        for (i in 0 until BAND_COUNT) {
            val low = 1f - i.toFloat() / BAND_COUNT
            val shimmer = 0.5f + 0.5f * sin(clock * (3f + i * 0.7f) + i)
            target[i] = (0.15f + 0.65f * kick * low + 0.25f * shimmer * (1f - low)).coerceIn(0f, 1f)
        }
    }

    private companion object {
        const val TAG = "DjAudioLevels"
        const val BAND_COUNT = 24
        const val LIVE_TIMEOUT_MS = 800L
        const val FALLBACK_BPM = 124f
    }
}
