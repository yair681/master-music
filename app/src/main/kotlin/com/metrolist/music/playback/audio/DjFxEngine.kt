/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

enum class DjFx(
    val oneShot: Boolean,
    /** Length of a one-shot in beats; 0 for toggles. */
    val beats: Int,
) {
    BASS_DROP(true, 4),
    STUTTER(true, 4),
    BUILD_UP(true, 8),
    DJ_DROP(true, 2),
    QUICK_CUTS(true, 8),
    BASS_BOOST(false, 0),
    ECHO(false, 0),
    REVERB(false, 0),
}

data class DjFxEvent(
    val fx: DjFx,
    val auto: Boolean,
    val endsAtElapsedMs: Long,
)

/**
 * Shared control surface between the UI / service and the [DjFxAudioProcessor] instances.
 * Everything here is read from the audio thread, so state is kept in thread-safe holders.
 */
class DjFxEngine {
    /** Master switch: processors pass audio through untouched unless this is true. */
    @Volatile
    var enabled = false

    /** Only the processor of the player that is currently audible receives one-shots. */
    @Volatile
    var primary: DjFxAudioProcessor? = null

    private val _intensity = MutableStateFlow(0.7f)
    val intensity: StateFlow<Float> = _intensity.asStateFlow()

    private val _toggles = MutableStateFlow<Set<DjFx>>(emptySet())
    val toggles: StateFlow<Set<DjFx>> = _toggles.asStateFlow()

    private val _autoFx = MutableStateFlow(true)
    val autoFx: StateFlow<Boolean> = _autoFx.asStateFlow()

    private val _smartTransitions = MutableStateFlow(true)
    val smartTransitions: StateFlow<Boolean> = _smartTransitions.asStateFlow()

    private val _lastEvent = MutableStateFlow<DjFxEvent?>(null)
    val lastEvent: StateFlow<DjFxEvent?> = _lastEvent.asStateFlow()

    /** Measured tempo of the audible track, or 0 until the detector is confident. */
    private val _bpm = MutableStateFlow(0f)
    val bpm: StateFlow<Float> = _bpm.asStateFlow()

    /** True once the audible player's processor accepted the stream format. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    internal val pendingShots = ConcurrentLinkedQueue<DjFx>()

    fun setIntensity(value: Float) {
        _intensity.value = value.coerceIn(0f, 1f)
    }

    fun setAutoFx(on: Boolean) {
        _autoFx.value = on
    }

    fun setSmartTransitions(on: Boolean) {
        _smartTransitions.value = on
    }

    fun toggle(fx: DjFx) {
        require(!fx.oneShot)
        _toggles.value = _toggles.value.let { if (fx in it) it - fx else it + fx }
    }

    /** Returns false when no audible processor can play the effect. */
    fun trigger(
        fx: DjFx,
        auto: Boolean = false,
    ): Boolean {
        require(fx.oneShot)
        if (!enabled || !_ready.value) return false
        pendingShots.add(fx)
        val beatMs = 60_000f / (_bpm.value.takeIf { it > 0f } ?: DEFAULT_BPM)
        // Up to one beat of quantization delay before the effect starts.
        val durationMs = ((fx.beats + 1) * beatMs).toLong()
        _lastEvent.value = DjFxEvent(fx, auto, SystemClock.elapsedRealtime() + durationMs)
        return true
    }

    fun reset() {
        pendingShots.clear()
        _toggles.value = emptySet()
        _lastEvent.value = null
        _bpm.value = 0f
    }

    internal fun isOn(fx: DjFx) = fx in _toggles.value

    internal fun reportTempo(
        source: DjFxAudioProcessor,
        bpm: Float,
    ) {
        if (source === primary) _bpm.value = bpm
    }

    internal fun reportReady(
        source: DjFxAudioProcessor,
        ready: Boolean,
    ) {
        if (source === primary) _ready.value = ready
    }

    /** Called when the audible player changes (startup or crossfade swap). */
    fun onPrimaryChanged(processor: DjFxAudioProcessor?) {
        primary = processor
        _ready.value = processor?.isFormatSupported == true
        _bpm.value = processor?.lockedBpm ?: 0f
    }

    companion object {
        const val DEFAULT_BPM = 120f
    }
}
