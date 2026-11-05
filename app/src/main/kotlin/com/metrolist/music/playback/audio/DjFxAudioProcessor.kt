/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Real-time DJ effects on the decoded PCM stream of one player.
 *
 * When [DjFxEngine.enabled] is false and no tail is ringing, audio is copied through
 * bit-for-bit, so normal playback is unaffected. It also tracks the tempo of the stream
 * (onset autocorrelation) so beat-based effects follow the actual song.
 */
@UnstableApi
class DjFxAudioProcessor(
    private val engine: DjFxEngine,
) : BaseAudioProcessor() {
    private var sampleRate = 0
    private var channels = 0

    var isFormatSupported = false
        private set

    // ---- Tempo tracking ----
    private var frameCounter = 0L
    private var hopFrames = 441
    private var hopAcc = 0f
    private var hopCount = 0
    private var analysisLp = 0f
    private var analysisAlpha = 0f
    private var prevEnvelope = 0f
    private val onsets = FloatArray(ONSET_HISTORY)
    private var onsetWrite = 0
    private var onsetFilled = 0
    private var totalHops = 0L
    private var hopsSinceAnalysis = 0
    private var bpm = DjFxEngine.DEFAULT_BPM
    private var tempoLocked = false
    private var beatAnchorFrame = 0L

    val lockedBpm: Float
        get() = if (tempoLocked) bpm else 0f

    // ---- One-shot state ----
    private var shot: DjFx? = null
    private var shotStart = 0L
    private var shotLength = 0L
    private var shotBeatFrames = 0f
    private var pendingShot: DjFx? = null
    private var pendingStart = 0L

    // Stutter: captured slice that is replayed while the song keeps running underneath.
    private var sliceL = FloatArray(0)
    private var sliceR = FloatArray(0)
    private var sliceLength = 0
    private var sliceSegment = -1

    // Sub "boom" oscillator for the bass drop.
    private var boomPhase = 0.0

    // ---- Transition requests from the service (crossfade) ----
    @Volatile
    private var transitionRequest = 0 // 1 = out, 2 = in

    @Volatile
    private var transitionRequestMs = 0L
    private var transition = 0
    private var transitionStart = 0L
    private var transitionLength = 1L

    // ---- Filters ----
    private val lowShelf = Biquad()
    private val highPass = Biquad()
    private var lowShelfDb = 0f
    private var highPassHz = 0f

    // ---- Echo ----
    private var echoL = FloatArray(0)
    private var echoR = FloatArray(0)
    private var echoWrite = 0
    private var echoSend = 0f

    // ---- Reverb (Freeverb style: 4 combs + 2 allpasses per side) ----
    private val combsL = Array(4) { DelayLine() }
    private val combsR = Array(4) { DelayLine() }
    private val allpassL = Array(2) { DelayLine() }
    private val allpassR = Array(2) { DelayLine() }
    private val combStoreL = FloatArray(4)
    private val combStoreR = FloatArray(4)
    private var reverbSend = 0f

    // ---- Gate (cuts / drops) ----
    private var gate = 1f
    private var gateCoef = 0f

    private var wasEnabled = false
    private var tailFrames = 0L

    fun requestTransitionOut(durationMs: Long) {
        transitionRequestMs = durationMs
        transitionRequest = 1
    }

    fun requestTransitionIn(durationMs: Long) {
        transitionRequestMs = durationMs
        transitionRequest = 2
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            isFormatSupported = false
            sampleRate = 0
            engine.reportReady(this, false)
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        isFormatSupported = true
        engine.reportReady(this, true)

        hopFrames = sampleRate / HOPS_PER_SECOND
        analysisAlpha = 1f - exp(-2f * PI.toFloat() * 150f / sampleRate)
        gateCoef = 1f - exp(-1f / (0.004f * sampleRate))

        val sliceMax = sampleRate // up to one second
        sliceL = FloatArray(sliceMax)
        sliceR = FloatArray(sliceMax)
        val echoMax = (sampleRate * 2.5f).toInt()
        echoL = FloatArray(echoMax)
        echoR = FloatArray(echoMax)

        val scale = sampleRate / 44100f
        COMB_TUNING.forEachIndexed { i, len ->
            combsL[i].allocate((len * scale).toInt())
            combsR[i].allocate(((len + STEREO_SPREAD) * scale).toInt())
        }
        ALLPASS_TUNING.forEachIndexed { i, len ->
            allpassL[i].allocate((len * scale).toInt())
            allpassR[i].allocate(((len + STEREO_SPREAD) * scale).toInt())
        }
        clearState(resetTempo = true)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)

        val enabled = engine.enabled && isFormatSupported
        if (!enabled) {
            if (wasEnabled) {
                clearState(resetTempo = false)
                wasEnabled = false
            }
            output.put(inputBuffer)
            output.flip()
            return
        }
        wasEnabled = true

        val isPrimary = engine.primary === this
        if (isPrimary) {
            while (true) {
                val fx = engine.pendingShots.poll() ?: break
                scheduleShot(fx)
            }
        }
        consumeTransitionRequest()

        val intensity = engine.intensity.value
        val bassBoostOn = engine.isOn(DjFx.BASS_BOOST)
        val echoOn = engine.isOn(DjFx.ECHO)
        val reverbOn = engine.isOn(DjFx.REVERB)

        val frames = remaining / (2 * channels)
        for (i in 0 until frames) {
            val rawL = inputBuffer.getShort()
            val rawR = if (channels == 2) inputBuffer.getShort() else rawL
            val inL = rawL / 32768f
            val inR = rawR / 32768f

            analyzeTempo((inL + inR) * 0.5f)

            if (pendingShot != null && frameCounter >= pendingStart) startShot()
            if (shot != null && frameCounter >= shotStart + shotLength) endShot()
            if (transition != 0 && frameCounter >= transitionStart + transitionLength) transition = 0

            if (i and CONTROL_MASK == 0) updateControls(intensity, bassBoostOn, echoOn, reverbOn)

            if (!isProcessing()) {
                // Nothing audible is active: keep the original samples bit-for-bit.
                output.putShort(rawL)
                if (channels == 2) output.putShort(rawR)
                frameCounter++
                continue
            }

            var l = inL
            var r = inR

            // Stutter replaces the live signal with a looped slice.
            if (shot == DjFx.STUTTER) {
                val elapsed = (frameCounter - shotStart).toInt()
                val segment = stutterSegment(elapsed)
                if (segment != sliceSegment) {
                    sliceSegment = segment
                    sliceLength = stutterSliceFrames(segment).coerceIn(32, sliceL.size)
                }
                val segStart = stutterSegmentStart(segment)
                val pos = (elapsed - segStart)
                val idx = pos % sliceLength
                if (pos < sliceLength) {
                    sliceL[idx] = l
                    sliceR[idx] = r
                }
                val fade = sliceFade(idx, sliceLength)
                l = sliceL[idx] * fade
                r = sliceR[idx] * fade
            }

            // Gate for quick cuts / DJ drop / end of build-up.
            gate += (gateTarget() - gate) * gateCoef
            l *= gate
            r *= gate

            if (highPassHz > 0f) {
                l = highPass.process(l, 0)
                r = highPass.process(r, 1)
            }
            if (lowShelfDb > 0.1f) {
                l = lowShelf.process(l, 0)
                r = lowShelf.process(r, 1)
            }

            if (shot == DjFx.BASS_DROP) {
                val t = (frameCounter - shotStart) / sampleRate.toFloat()
                val freq = 38.0 + 32.0 * exp(-t * 3.0)
                boomPhase += 2.0 * PI * freq / sampleRate
                if (boomPhase > 2.0 * PI) boomPhase -= 2.0 * PI
                val boom = (sin(boomPhase) * exp(-t * 1.6) * 0.55 * intensity).toFloat()
                l += boom
                r += boom
            }

            // Echo: dotted eighth note feedback delay.
            if (echoSend > 0f || tailFrames > 0) {
                val delay = (shotBeatFramesOrCurrent() * 0.75f).toInt().coerceIn(1, echoL.size - 1)
                var read = echoWrite - delay
                if (read < 0) read += echoL.size
                val wetL = echoL[read]
                val wetR = echoR[read]
                echoL[echoWrite] = l * echoSend + wetR * ECHO_FEEDBACK
                echoR[echoWrite] = r * echoSend + wetL * ECHO_FEEDBACK
                echoWrite = (echoWrite + 1) % echoL.size
                l += wetL * ECHO_MIX
                r += wetR * ECHO_MIX
            }

            if (reverbSend > 0f || tailFrames > 0) {
                val input = (l + r) * 0.5f * reverbSend * REVERB_INPUT_GAIN
                var outL = 0f
                var outR = 0f
                for (c in 0 until 4) {
                    outL += combsL[c].comb(input, combStoreL, c)
                    outR += combsR[c].comb(input, combStoreR, c)
                }
                for (a in 0 until 2) {
                    outL = allpassL[a].allpass(outL)
                    outR = allpassR[a].allpass(outR)
                }
                l += outL * REVERB_WET
                r += outR * REVERB_WET
            }

            if (tailFrames > 0) tailFrames--

            val outL = softClip(l)
            val outR = softClip(r)
            output.putShort((outL * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            if (channels == 2) output.putShort((outR * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            frameCounter++
        }
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // Seek or new track: old audio must not ring into the new position.
        clearState(resetTempo = true)
    }

    override fun onReset() {
        sampleRate = 0
        channels = 0
        isFormatSupported = false
        clearState(resetTempo = true)
    }

    private fun clearState(resetTempo: Boolean) {
        shot = null
        pendingShot = null
        transition = 0
        sliceSegment = -1
        gate = 1f
        lowShelfDb = 0f
        highPassHz = 0f
        echoSend = 0f
        reverbSend = 0f
        tailFrames = 0
        lowShelf.reset()
        highPass.reset()
        echoL.fill(0f)
        echoR.fill(0f)
        echoWrite = 0
        combsL.forEach { it.clear() }
        combsR.forEach { it.clear() }
        allpassL.forEach { it.clear() }
        allpassR.forEach { it.clear() }
        combStoreL.fill(0f)
        combStoreR.fill(0f)
        if (resetTempo) {
            onsets.fill(0f)
            onsetWrite = 0
            onsetFilled = 0
            totalHops = 0
            hopsSinceAnalysis = 0
            hopAcc = 0f
            hopCount = 0
            prevEnvelope = 0f
            analysisLp = 0f
            tempoLocked = false
            bpm = DjFxEngine.DEFAULT_BPM
            frameCounter = 0
            beatAnchorFrame = 0
            engine.reportTempo(this, 0f)
        }
    }

    // ---------------------------------------------------------------- shots

    private fun isProcessing(): Boolean =
        shot != null ||
            transition != 0 ||
            tailFrames > 0 ||
            lowShelfDb > 0.1f ||
            highPassHz > 0f ||
            echoSend > 0f ||
            reverbSend > 0f ||
            gate < 0.999f

    private fun beatFrames(): Float = sampleRate * 60f / bpm

    private fun shotBeatFramesOrCurrent(): Float = if (shot != null) shotBeatFrames else beatFrames()

    private fun scheduleShot(fx: DjFx) {
        if (shot != null || pendingShot != null) return
        val beat = beatFrames()
        val start =
            if (tempoLocked && frameCounter > beatAnchorFrame) {
                val beatsSince = (frameCounter - beatAnchorFrame) / beat
                beatAnchorFrame + (ceil(beatsSince) * beat).toLong()
            } else {
                frameCounter
            }
        pendingShot = fx
        pendingStart = start
    }

    private fun startShot() {
        val fx = pendingShot ?: return
        pendingShot = null
        shot = fx
        shotStart = frameCounter
        shotBeatFrames = beatFrames()
        shotLength = (fx.beats * shotBeatFrames).toLong()
        sliceSegment = -1
        boomPhase = 0.0
    }

    private fun endShot() {
        val finished = shot
        shot = null
        tailFrames = (sampleRate * 3L)
        if (finished == DjFx.BUILD_UP) {
            // A build-up resolves into a drop on the next beat.
            pendingShot = DjFx.BASS_DROP
            pendingStart = frameCounter
        }
    }

    private fun shotProgress(): Float = ((frameCounter - shotStart).toFloat() / shotLength).coerceIn(0f, 1f)

    /** Stutter roll: two beats of 1/8 notes, one beat of 1/16, one beat of 1/32. */
    private fun stutterSegment(elapsed: Int): Int {
        val beat = shotBeatFrames
        return when {
            elapsed < beat * 2 -> (elapsed / (beat / 2)).toInt()
            elapsed < beat * 3 -> 4 + ((elapsed - beat * 2) / (beat / 4)).toInt()
            else -> 8 + ((elapsed - beat * 3) / (beat / 8)).toInt()
        }
    }

    private fun stutterSegmentStart(segment: Int): Int {
        val beat = shotBeatFrames
        return when {
            segment < 4 -> (segment * beat / 2).toInt()
            segment < 8 -> (beat * 2 + (segment - 4) * beat / 4).toInt()
            else -> (beat * 3 + (segment - 8) * beat / 8).toInt()
        }
    }

    private fun stutterSliceFrames(segment: Int): Int {
        val beat = shotBeatFrames
        return when {
            segment < 4 -> (beat / 4).toInt() // repeats a 1/16 slice inside each 1/8
            segment < 8 -> (beat / 8).toInt()
            else -> (beat / 16).toInt()
        }
    }

    private fun sliceFade(
        idx: Int,
        length: Int,
    ): Float {
        val ramp = (sampleRate * 0.003f).toInt().coerceAtLeast(1)
        return when {
            idx < ramp -> idx / ramp.toFloat()
            idx > length - ramp -> (length - idx) / ramp.toFloat()
            else -> 1f
        }
    }

    private fun gateTarget(): Float {
        val current = shot ?: return 1f
        val elapsed = frameCounter - shotStart
        val beat = shotBeatFrames
        return when (current) {
            DjFx.DJ_DROP -> if (elapsed < beat) 0f else 1f
            DjFx.QUICK_CUTS -> {
                val step = ((elapsed / (beat / 4)).toInt()) % CUT_PATTERN.size
                if (CUT_PATTERN[step]) 1f else 0f
            }
            DjFx.BUILD_UP -> {
                // Last two beats: accelerating 1/16 gate for tension.
                if (elapsed > beat * 6) {
                    val step = (elapsed / (beat / 4)).toInt()
                    if (step % 2 == 0) 1f else 0.15f
                } else {
                    1f
                }
            }
            else -> 1f
        }
    }

    // ------------------------------------------------------------ controls

    private fun consumeTransitionRequest() {
        val request = transitionRequest
        if (request == 0) return
        transitionRequest = 0
        transition = request
        transitionStart = frameCounter
        transitionLength = (transitionRequestMs * sampleRate / 1000L).coerceAtLeast(1L)
    }

    private fun updateControls(
        intensity: Float,
        bassBoostOn: Boolean,
        echoOn: Boolean,
        reverbOn: Boolean,
    ) {
        var shelf = if (bassBoostOn) 4f + 6f * intensity else 0f
        var hp = 0f
        var echo = if (echoOn) 0.25f + 0.45f * intensity else 0f
        val reverb = if (reverbOn) 0.35f + 0.65f * intensity else 0f

        when (shot) {
            DjFx.BASS_DROP -> shelf = maxOf(shelf, (6f + 8f * intensity) * (1f - shotProgress()))
            DjFx.DJ_DROP -> {
                val elapsed = frameCounter - shotStart
                if (elapsed >= shotBeatFrames) shelf = maxOf(shelf, (4f + 6f * intensity) * (1f - shotProgress()))
            }
            DjFx.BUILD_UP -> {
                val p = shotProgress()
                hp = 30f * (1500f / 30f).pow(p * (0.5f + 0.5f * intensity))
            }
            else -> Unit
        }

        if (transition != 0) {
            val p = ((frameCounter - transitionStart).toFloat() / transitionLength).coerceIn(0f, 1f)
            if (transition == 1) {
                // Outgoing track: bass rolls off and it trails away on echoes.
                hp = maxOf(hp, 30f * (900f / 30f).pow(p))
                if (p > 0.4f) echo = maxOf(echo, 0.6f * intensity)
            } else {
                // Incoming track: its bass stays out until the outgoing bass is gone.
                val cut = 1f - (p / 0.7f).coerceIn(0f, 1f)
                if (cut > 0f) hp = maxOf(hp, 30f + 220f * cut)
            }
        }

        if (abs(shelf - lowShelfDb) > 0.05f) {
            if (lowShelfDb <= 0.1f && shelf > 0.1f) lowShelf.reset()
            lowShelfDb = shelf
            if (shelf > 0.1f) lowShelf.setLowShelf(sampleRate, 110f, shelf)
        }
        if (abs(hp - highPassHz) > 0.5f || (hp == 0f && highPassHz != 0f)) {
            if (hp > 0f) {
                if (highPassHz == 0f) highPass.reset()
                highPass.setHighPass(sampleRate, hp.coerceAtMost(sampleRate * 0.45f), 0.8f)
            }
            highPassHz = hp
        }
        echoSend = echo
        reverbSend = reverb
        if (echo > 0f || reverb > 0f) tailFrames = maxOf(tailFrames, sampleRate * 3L)
    }

    // --------------------------------------------------------------- tempo

    private fun analyzeTempo(mono: Float) {
        analysisLp += (mono - analysisLp) * analysisAlpha
        hopAcc += analysisLp * analysisLp
        hopCount++
        if (hopCount < hopFrames) return

        val envelope = sqrt(hopAcc / hopCount)
        hopAcc = 0f
        hopCount = 0
        val onset = (envelope - prevEnvelope).coerceAtLeast(0f)
        prevEnvelope = envelope
        onsets[onsetWrite] = onset
        onsetWrite = (onsetWrite + 1) % ONSET_HISTORY
        if (onsetFilled < ONSET_HISTORY) onsetFilled++
        totalHops++

        if (++hopsSinceAnalysis >= ANALYSIS_INTERVAL_HOPS && onsetFilled >= MIN_ANALYSIS_HOPS) {
            hopsSinceAnalysis = 0
            estimateTempo()
        }
    }

    private fun onsetAt(hopsAgo: Int): Float {
        var idx = onsetWrite - 1 - hopsAgo
        while (idx < 0) idx += ONSET_HISTORY
        return onsets[idx]
    }

    private fun estimateTempo() {
        val n = onsetFilled
        var energy = 0f
        for (i in 0 until n) energy += onsetAt(i) * onsetAt(i)
        if (energy <= 1e-9f) return

        var bestLag = 0
        var bestScore = 0f
        for (lag in MIN_LAG..MAX_LAG) {
            var score = 0f
            for (i in 0 until n - lag) score += onsetAt(i) * onsetAt(i + lag)
            // Mild preference for tempos near 120 BPM to avoid half/double errors.
            val bpmAtLag = 60f * HOPS_PER_SECOND / lag
            val weight = exp(-0.5f * ((ln2(bpmAtLag / 120f)) / 0.6f).let { it * it })
            score *= weight
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag == 0 || bestScore / energy < MIN_CONFIDENCE) return

        val newBpm = 60f * HOPS_PER_SECOND / bestLag
        bpm = if (tempoLocked && abs(newBpm - bpm) < 4f) bpm * 0.7f + newBpm * 0.3f else newBpm
        tempoLocked = true

        // Beat phase: the offset within one period that collects the most onset energy.
        var bestOffset = 0
        var bestPhase = -1f
        for (offset in 0 until bestLag) {
            var sum = 0f
            var k = offset
            while (k < n) {
                sum += onsetAt(k)
                k += bestLag
            }
            if (sum > bestPhase) {
                bestPhase = sum
                bestOffset = offset
            }
        }
        val latestHopFrame = frameCounter - (frameCounter % hopFrames)
        beatAnchorFrame = latestHopFrame - bestOffset.toLong() * hopFrames
        engine.reportTempo(this, bpm)
    }

    private fun ln2(x: Float): Float = (kotlin.math.ln(x.toDouble()) / kotlin.math.ln(2.0)).toFloat()

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= SOFT_KNEE) return x
        val shaped = SOFT_KNEE + (1f - SOFT_KNEE) * tanh((a - SOFT_KNEE) / (1f - SOFT_KNEE))
        return if (x < 0) -shaped else shaped
    }

    // --------------------------------------------------------------- DSP

    /** RBJ biquad with independent state for two channels. */
    private class Biquad {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private val x1 = FloatArray(2)
        private val x2 = FloatArray(2)
        private val y1 = FloatArray(2)
        private val y2 = FloatArray(2)

        fun reset() {
            x1.fill(0f)
            x2.fill(0f)
            y1.fill(0f)
            y2.fill(0f)
        }

        fun setLowShelf(
            sampleRate: Int,
            freq: Float,
            gainDb: Float,
        ) {
            val a = 10.0.pow(gainDb / 40.0)
            val w = 2.0 * PI * freq / sampleRate
            val cw = cos(w)
            val alpha = sin(w) / 2.0 * sqrt(2.0)
            val sa = 2.0 * sqrt(a) * alpha
            val n0 = (a + 1) + (a - 1) * cw + sa
            set(
                a * ((a + 1) - (a - 1) * cw + sa) / n0,
                2 * a * ((a - 1) - (a + 1) * cw) / n0,
                a * ((a + 1) - (a - 1) * cw - sa) / n0,
                -2 * ((a - 1) + (a + 1) * cw) / n0,
                ((a + 1) + (a - 1) * cw - sa) / n0,
            )
        }

        fun setHighPass(
            sampleRate: Int,
            freq: Float,
            q: Float,
        ) {
            val w = 2.0 * PI * freq / sampleRate
            val cw = cos(w)
            val alpha = sin(w) / (2.0 * q)
            val n0 = 1 + alpha
            set(
                (1 + cw) / 2 / n0,
                -(1 + cw) / n0,
                (1 + cw) / 2 / n0,
                -2 * cw / n0,
                (1 - alpha) / n0,
            )
        }

        private fun set(
            nb0: Double,
            nb1: Double,
            nb2: Double,
            na1: Double,
            na2: Double,
        ) {
            b0 = nb0.toFloat()
            b1 = nb1.toFloat()
            b2 = nb2.toFloat()
            a1 = na1.toFloat()
            a2 = na2.toFloat()
        }

        fun process(
            x: Float,
            ch: Int,
        ): Float {
            val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
            x2[ch] = x1[ch]
            x1[ch] = x
            y2[ch] = y1[ch]
            y1[ch] = if (abs(y) < 1e-20f) 0f else y
            return y
        }
    }

    private class DelayLine {
        private var buffer = FloatArray(1)
        private var index = 0

        fun allocate(length: Int) {
            buffer = FloatArray(length.coerceAtLeast(1))
            index = 0
        }

        fun clear() {
            buffer.fill(0f)
            index = 0
        }

        fun comb(
            input: Float,
            store: FloatArray,
            slot: Int,
        ): Float {
            val out = buffer[index]
            store[slot] = out * (1f - REVERB_DAMP) + store[slot] * REVERB_DAMP
            buffer[index] = input + store[slot] * REVERB_FEEDBACK
            if (++index >= buffer.size) index = 0
            return out
        }

        fun allpass(input: Float): Float {
            val buffered = buffer[index]
            val out = -input + buffered
            buffer[index] = input + buffered * 0.5f
            if (++index >= buffer.size) index = 0
            return out
        }
    }

    private companion object {
        const val CONTROL_MASK = 63
        const val HOPS_PER_SECOND = 100
        const val ONSET_HISTORY = 800 // 8 seconds
        const val MIN_ANALYSIS_HOPS = 500
        const val ANALYSIS_INTERVAL_HOPS = 200
        const val MIN_LAG = 33 // ~182 BPM
        const val MAX_LAG = 80 // 75 BPM
        const val MIN_CONFIDENCE = 0.08f

        const val ECHO_FEEDBACK = 0.42f
        const val ECHO_MIX = 0.45f

        const val REVERB_FEEDBACK = 0.84f
        const val REVERB_DAMP = 0.25f
        const val REVERB_INPUT_GAIN = 0.05f
        const val REVERB_WET = 1.0f
        const val STEREO_SPREAD = 23
        val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356)
        val ALLPASS_TUNING = intArrayOf(556, 441)

        const val SOFT_KNEE = 0.85f

        val CUT_PATTERN =
            booleanArrayOf(
                true, true, false, true, true, false, true, false,
                true, true, false, true, false, true, true, false,
            )
    }
}
