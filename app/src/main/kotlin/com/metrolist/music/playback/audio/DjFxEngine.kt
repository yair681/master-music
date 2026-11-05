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
import java.util.concurrent.atomic.AtomicInteger

enum class DjFx(
    val oneShot: Boolean,
    /** Choices for the length control, in beats (one-shots) or the effect's own unit. */
    val options: FloatArray,
    val optionLabels: Array<String>,
    val visible: Boolean = true,
) {
    BASS_DROP(true, floatArrayOf(2f, 4f, 8f), arrayOf("2", "4", "8")),
    STUTTER(true, floatArrayOf(2f, 4f, 8f), arrayOf("2", "4", "8")),
    BUILD_UP(true, floatArrayOf(4f, 8f, 16f), arrayOf("4", "8", "16")),
    DJ_DROP(true, floatArrayOf(0.5f, 1f, 2f), arrayOf("½", "1", "2")),
    QUICK_CUTS(true, floatArrayOf(4f, 8f, 16f), arrayOf("4", "8", "16")),
    BASS_BOOST(false, floatArrayOf(60f, 100f, 150f), arrayOf("60Hz", "100Hz", "150Hz")),
    ECHO(false, floatArrayOf(0.5f, 0.75f, 1f), arrayOf("1/8", "1/8·", "1/4")),
    REVERB(false, floatArrayOf(0.78f, 0.85f, 0.91f), arrayOf("S", "M", "L")),
    ECHO_OUT(true, floatArrayOf(2f, 4f, 8f), arrayOf("2", "4", "8"), visible = false),
}

enum class DjFxPreset(
    val amount: Float,
    val option: Int,
) {
    LIGHT(0.45f, 0),
    CLUB(0.75f, 1),
    EXTREME(1f, 1),
}

data class DjFxEvent(
    val fx: DjFx,
    val auto: Boolean,
    val endsAtElapsedMs: Long,
)

/**
 * Control surface shared by the UI, the service and every [DjFxAudioProcessor].
 *
 * The audio thread only ever reads plain volatile fields and array references from here,
 * and only writes an [AtomicInteger]; it never touches a StateFlow, a lock or the heap.
 */
class DjFxEngine {
    @Volatile
    var enabled = false

    @Volatile
    var primary: DjFxAudioProcessor? = null

    // ---- Settings read by the audio thread (arrays are replaced, never mutated) ----
    @Volatile
    internal var amounts = FloatArray(DjFx.entries.size) { DjFxPreset.CLUB.amount }

    @Volatile
    internal var options = IntArray(DjFx.entries.size) { DjFxPreset.CLUB.option }

    @Volatile
    internal var toggleMask = 0

    /** Encoded commands: ordinal, plus [APPEND] to queue after the running effect. */
    internal val pendingShots = ConcurrentLinkedQueue<Int>()

    /** Set by a finishing build-up; consumed by whichever processor is audible then. */
    internal val dropRequest = AtomicInteger(0)

    // ---- UI state ----
    private val _settingsVersion = MutableStateFlow(0)
    val settingsVersion: StateFlow<Int> = _settingsVersion.asStateFlow()

    private val _preset = MutableStateFlow<DjFxPreset?>(DjFxPreset.CLUB)
    val preset: StateFlow<DjFxPreset?> = _preset.asStateFlow()

    private val _toggles = MutableStateFlow<Set<DjFx>>(emptySet())
    val toggles: StateFlow<Set<DjFx>> = _toggles.asStateFlow()

    private val _autoFx = MutableStateFlow(true)
    val autoFx: StateFlow<Boolean> = _autoFx.asStateFlow()

    private val _smartTransitions = MutableStateFlow(true)
    val smartTransitions: StateFlow<Boolean> = _smartTransitions.asStateFlow()

    private val _lastEvent = MutableStateFlow<DjFxEvent?>(null)
    val lastEvent: StateFlow<DjFxEvent?> = _lastEvent.asStateFlow()

    private val _bpm = MutableStateFlow(0f)
    val bpm: StateFlow<Float> = _bpm.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun amountOf(fx: DjFx) = amounts[fx.ordinal]

    fun optionOf(fx: DjFx) = options[fx.ordinal]

    fun setAmount(
        fx: DjFx,
        value: Float,
    ) {
        amounts = amounts.copyOf().also { it[fx.ordinal] = value.coerceIn(0f, 1f) }
        if (fx == DjFx.ECHO) amounts = amounts.copyOf().also { it[DjFx.ECHO_OUT.ordinal] = value.coerceIn(0f, 1f) }
        _preset.value = null
        _settingsVersion.value++
    }

    fun setOption(
        fx: DjFx,
        index: Int,
    ) {
        options = options.copyOf().also { it[fx.ordinal] = index.coerceIn(0, fx.options.size - 1) }
        _preset.value = null
        _settingsVersion.value++
    }

    fun applyPreset(preset: DjFxPreset) {
        amounts = FloatArray(DjFx.entries.size) { preset.amount }
        options =
            IntArray(DjFx.entries.size) {
                // Extreme keeps standard lengths but always uses the longest echo and room.
                if (preset == DjFxPreset.EXTREME && (it == DjFx.ECHO.ordinal || it == DjFx.REVERB.ordinal)) 2 else preset.option
            }
        _preset.value = preset
        _settingsVersion.value++
    }

    fun setAutoFx(on: Boolean) {
        _autoFx.value = on
    }

    fun setSmartTransitions(on: Boolean) {
        _smartTransitions.value = on
    }

    /**
     * Called by the service when a DJ crossfade swaps players. Applies the transition
     * filters and echo only while the Smart Transitions switch is on.
     */
    fun startSmartTransition(
        outgoing: DjFxAudioProcessor?,
        incoming: DjFxAudioProcessor?,
        durationMs: Long,
    ): Boolean {
        if (!enabled || !_smartTransitions.value) return false
        outgoing?.requestTransitionOut(durationMs)
        incoming?.requestTransitionIn(durationMs)
        return true
    }

    fun toggle(fx: DjFx) {
        require(!fx.oneShot)
        val next = _toggles.value.let { if (fx in it) it - fx else it + fx }
        _toggles.value = next
        toggleMask = next.fold(0) { mask, t -> mask or (1 shl t.ordinal) }
    }

    /** Starts [fx] now, interrupting a running one-shot. Returns false if nothing can play it. */
    fun trigger(
        fx: DjFx,
        auto: Boolean = false,
    ): Boolean = triggerSequence(listOf(fx), auto)

    /** Plays the effects back to back, each starting on the beat the previous one ends. */
    fun triggerSequence(
        sequence: List<DjFx>,
        auto: Boolean = false,
    ): Boolean {
        require(sequence.isNotEmpty() && sequence.all { it.oneShot })
        if (!enabled || !_ready.value) return false
        sequence.forEachIndexed { i, fx -> enqueue(fx, append = i > 0) }
        val beatMs = 60_000f / (_bpm.value.takeIf { it > 0f } ?: DEFAULT_BPM)
        val beats = sequence.sumOf { lengthInBeats(it).toDouble() }.toFloat()
        _lastEvent.value =
            DjFxEvent(sequence.first(), auto, SystemClock.elapsedRealtime() + ((beats + 1f) * beatMs).toLong())
        return true
    }

    internal fun enqueue(
        fx: DjFx,
        append: Boolean = false,
    ) {
        pendingShots.add(fx.ordinal or (if (append) APPEND else 0))
    }

    /** Total length of a one-shot in beats with the current settings. */
    fun lengthInBeats(fx: DjFx): Float {
        val option = fx.options[options[fx.ordinal]]
        return when (fx) {
            DjFx.DJ_DROP -> 0.5f + option + 2f
            else -> option
        }
    }

    fun reset() {
        pendingShots.clear()
        dropRequest.set(0)
        _toggles.value = emptySet()
        toggleMask = 0
        _lastEvent.value = null
        _bpm.value = 0f
    }

    internal fun isOn(fx: DjFx) = toggleMask and (1 shl fx.ordinal) != 0

    /** Called from the tempo analysis thread, never from the audio thread. */
    internal fun publishTempo(
        source: DjFxAudioProcessor,
        bpm: Float,
    ) {
        if (source === primary) _bpm.value = bpm
    }

    /** Called from processor configuration (not the render loop). */
    internal fun reportReady(
        source: DjFxAudioProcessor,
        ready: Boolean,
    ) {
        if (source === primary) _ready.value = ready
    }

    fun onPrimaryChanged(processor: DjFxAudioProcessor?) {
        primary = processor
        _ready.value = processor?.isFormatSupported == true
        _bpm.value = processor?.lockedBpm ?: 0f
    }

    companion object {
        const val DEFAULT_BPM = 120f
        internal const val APPEND = 0x100
    }
}
