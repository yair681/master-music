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
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Real-time DJ effects on the decoded PCM of one player.
 *
 * Real-time rules followed here:
 *  - nothing on the render path allocates, locks, waits or touches a StateFlow;
 *  - buffers grow only in [onConfigure], never per buffer;
 *  - every parameter is smoothed, filters are zero-delay-feedback SVFs that stay
 *    stable under fast modulation, and entering/leaving processing crossfades with the
 *    dry signal, so switching effects never clicks;
 *  - tempo analysis runs on another thread ([runTempoAnalysis]); effects just read its
 *    latest result and never wait for it.
 * When no effect is active the input is copied through bit-for-bit.
 */
@UnstableApi
class DjFxAudioProcessor(
    private val engine: DjFxEngine,
) : BaseAudioProcessor() {
    private var sr = 0
    private var channels = 0

    @Volatile
    var isFormatSupported = false
        private set

    private var frame = 0L

    // ------------------------------------------------------------- tempo (audio side)
    private val onsetRing = FloatArray(ONSET_RING)

    @Volatile
    private var hopsWritten = 0L

    @Volatile
    private var tempoEpoch = 0
    private var hopFrames = 441
    private var hopAcc = 0f
    private var hopCount = 0
    private var analysisLp = 0f
    private var analysisAlpha = 0f
    private var prevEnvelope = 0f

    // ----------------------------------------------------- tempo (results, any thread)
    @Volatile
    private var bpm = DjFxEngine.DEFAULT_BPM

    @Volatile
    private var tempoLocked = false

    @Volatile
    private var beatAnchor = 0L

    val lockedBpm: Float
        get() = if (tempoLocked) bpm else 0f

    // Analysis scratch (only touched by the analysis thread).
    private val scratch = FloatArray(ONSET_WINDOW)

    // ---------------------------------------------------------------- buffers
    private var histL = FloatArray(0)
    private var histR = FloatArray(0)
    private var histMask = 0
    private var echoL = FloatArray(0)
    private var echoR = FloatArray(0)
    private var echoMask = 0
    private var echoWrite = 0
    private var preL = FloatArray(0)
    private var preMask = 0
    private var preWrite = 0
    private val combL = Array(8) { Comb() }
    private val combR = Array(8) { Comb() }
    private val apL = Array(4) { Allpass() }
    private val apR = Array(4) { Allpass() }

    // ---------------------------------------------------------------- filters
    private val hpL = Svf()
    private val hpR = Svf()
    private val shelfL = Svf()
    private val shelfR = Svf()
    private val darkL = Svf()
    private val darkR = Svf()
    private val noiseBp = Svf()
    private val revInHp = Svf()
    private var echoHpL = 0f
    private var echoHpR = 0f
    private var echoLpL = 0f
    private var echoLpR = 0f
    private var echoHpCoef = 0f
    private var echoLpCoef = 0f

    // ------------------------------------------------------------ smoothed params
    private var procMix = 0f
    private var processing = false
    private val hpMix = Ramp(0f)
    private var hpHz = 30f
    private var hpQ = 0.707f
    private val shelfGain = Ramp(1f)
    private var bassLimit = 1f
    private var shelfShelfHz = 100f
    private var bassEnv = 0f
    private val darkMix = Ramp(0f)
    private var darkHz = 18000f
    private var duck = 1f
    private val level = Ramp(1f)
    private val echoSend = Ramp(0f)
    private var echoFeedback = 0f
    private val echoMix = Ramp(0f)
    private var echoDelay = 0f
    private val echoDry = Ramp(1f)
    private val revSend = Ramp(0f)
    private var revFeedback = 0.84f
    private val revWet = Ramp(0f)
    private val noiseLevel = Ramp(0f)
    private var limiterGain = 1f
    private var gate = 1f
    private var gateTarget = 1f
    private var gateStep = 0f
    private var loopMix = 0f
    private var echoEnergy = 0f
    private var revEnergy = 0f

    // Per-block targets.
    private var tHpMix = 0f
    private var tHpHz = 30f
    private var tHpQ = 0.707f
    private var tShelfDb = 0f
    private var tShelfHz = 100f
    private var tDarkMix = 0f
    private var tDarkHz = 18000f
    private var tEchoSend = 0f
    private var tEchoFeedback = 0f
    private var tEchoMix = 0f
    private var tEchoDelay = 0f
    private var tEchoDry = 1f
    private var tRevSend = 0f
    private var tRevWet = 0f
    private var tNoise = 0f
    private var tGain = 1f
    private var gain = 1f

    // --------------------------------------------------------------- one-shots
    private val queue = IntArray(8)
    private var queueHead = 0
    private var queueSize = 0
    private var shot = -1
    private var shotStart = 0L
    private var shotLength = 1L
    private var shotBeat = 1f
    private var shotAmount = 0f
    private var shotOption = 1f
    private var startAt = -1L
    private var chained = false
    private var loopStart = 0L
    private var brakePos = 0.0
    private var subPhase = 0.0
    private var subStart = -1L
    private var subAmount = 0f
    private var subDecay = 1f
    private var duckStart = -1L
    private var duckDepth = 0f
    private var duckBeat = 1f
    private var shelfBurstStart = -1L
    private var shelfBurstLength = 1L
    private var shelfBurstDb = 0f
    private var cutPattern = 0
    private var rng = 0x2545F491

    // --------------------------------------------------------------- transitions
    @Volatile
    private var transitionRequest = 0

    @Volatile
    private var transitionRequestMs = 0L
    private var transition = 0
    private var transitionStart = 0L
    private var transitionLength = 1L

    fun requestTransitionOut(durationMs: Long) {
        transitionRequestMs = durationMs
        transitionRequest = TRANSITION_OUT
    }

    fun requestTransitionIn(durationMs: Long) {
        transitionRequestMs = durationMs
        transitionRequest = TRANSITION_IN
    }

    // =================================================================== setup

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            isFormatSupported = false
            engine.reportReady(this, false)
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sr = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount

        // Grow-only allocation: configuring again for a new track reuses everything.
        val histSize = nextPow2(sr * HISTORY_SECONDS)
        if (histL.size < histSize) {
            histL = FloatArray(histSize)
            histR = FloatArray(histSize)
        }
        histMask = histL.size - 1
        val echoSize = nextPow2(sr * ECHO_SECONDS)
        if (echoL.size < echoSize) {
            echoL = FloatArray(echoSize)
            echoR = FloatArray(echoSize)
        }
        echoMask = echoL.size - 1
        val preSize = nextPow2(sr / 20)
        if (preL.size < preSize) preL = FloatArray(preSize)
        preMask = preL.size - 1
        val scale = sr / 44100f
        for (i in 0 until 8) {
            combL[i].ensure((COMB_TUNING[i] * scale).toInt())
            combR[i].ensure(((COMB_TUNING[i] + STEREO_SPREAD) * scale).toInt())
        }
        for (i in 0 until 4) {
            apL[i].ensure((ALLPASS_TUNING[i] * scale).toInt())
            apR[i].ensure(((ALLPASS_TUNING[i] + STEREO_SPREAD) * scale).toInt())
        }

        hopFrames = sr / HOPS_PER_SECOND
        analysisAlpha = 1f - exp(-2f * PI.toFloat() * 150f / sr)
        gateStep = 1f / (GATE_RAMP_SECONDS * sr)
        echoHpCoef = 1f - exp(-2f * PI.toFloat() * 180f / sr)
        echoLpCoef = 1f - exp(-2f * PI.toFloat() * 4800f / sr)
        revInHp.set(sr, 250f, 0.707f)

        isFormatSupported = true
        engine.reportReady(this, true)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        resetState()
    }

    override fun onReset() {
        isFormatSupported = false
        resetState()
    }

    private fun resetState() {
        frame = 0
        tempoEpoch++
        hopsWritten = 0
        hopAcc = 0f
        hopCount = 0
        analysisLp = 0f
        prevEnvelope = 0f
        tempoLocked = false
        bpm = DjFxEngine.DEFAULT_BPM
        beatAnchor = 0
        queueSize = 0
        shot = -1
        startAt = -1
        subStart = -1
        duckStart = -1
        shelfBurstStart = -1
        transition = 0
        transitionRequest = 0
        processing = false
        procMix = 0f
        hpMix.set(0f)
        shelfGain.set(1f)
        darkMix.set(0f)
        duck = 1f
        level.set(1f)
        echoSend.set(0f)
        echoMix.set(0f)
        echoDry.set(1f)
        revSend.set(0f)
        revWet.set(0f)
        noiseLevel.set(0f)
        gate = 1f
        gateTarget = 1f
        loopMix = 0f
        gain = 1f
        limiterGain = 1f
        echoEnergy = 0f
        revEnergy = 0f
        echoDelay = 0f
        // Delay lines are cleared only here (seek / new track), never on the render path.
        echoL.fill(0f)
        echoR.fill(0f)
        preL.fill(0f)
        combL.forEach { it.clear() }
        combR.forEach { it.clear() }
        apL.forEach { it.clear() }
        apR.forEach { it.clear() }
        hpL.clear()
        hpR.clear()
        shelfL.clear()
        shelfR.clear()
        darkL.clear()
        darkR.clear()
        noiseBp.clear()
        revInHp.clear()
        echoHpL = 0f
        echoHpR = 0f
        echoLpL = 0f
        echoLpR = 0f
    }

    // ================================================================= render

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        if (!isFormatSupported) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        val enabled = engine.enabled
        if (enabled && engine.primary === this) pollCommands()
        if (enabled) consumeTransitionRequest()

        val amounts = engine.amounts
        val options = engine.options
        val toggles = if (enabled) engine.toggleMask else 0
        val frames = remaining / (2 * channels)

        for (i in 0 until frames) {
            val rawL = inputBuffer.getShort()
            val rawR = if (channels == 2) inputBuffer.getShort() else rawL
            val dryL = rawL / 32768f
            val dryR = rawR / 32768f

            val h = frame.toInt() and histMask
            histL[h] = dryL
            histR[h] = dryR
            analyzeOnset((dryL + dryR) * 0.5f)

            if (frame.toInt() and CONTROL_MASK == 0) {
                control(enabled, toggles, amounts, options)
            }

            if (!processing) {
                output.putShort(rawL)
                if (channels == 2) output.putShort(rawR)
                frame++
                continue
            }

            renderFrame(dryL, dryR, output)
            frame++
        }
        output.flip()
    }

    private fun renderFrame(
        dryL: Float,
        dryR: Float,
        output: ByteBuffer,
    ) {
        var l = dryL
        var r = dryR
        val hpMixV = hpMix.step()
        val shelfV = shelfGain.step()
        val darkV = darkMix.step()
        val levelV = level.step()
        val echoSendV = echoSend.step()
        val echoMixV = echoMix.step()
        val echoDryV = echoDry.step()
        val revSendV = revSend.step()
        val revWetV = revWet.step()
        val noiseV = noiseLevel.step()

        // ---- source: stutter loop or vinyl brake read from the history ring
        val current = shot
        if (current == FX_STUTTER || loopMix > 0f) {
            val loopTarget = if (current == FX_STUTTER) 1f else 0f
            loopMix += (loopTarget - loopMix).coerceIn(-LOOP_SLEW, LOOP_SLEW)
            if (loopMix > 0f) {
                val e = (frame - shotStart).toInt().coerceAtLeast(0)
                val slice = stutterSlice(e)
                val p = e % slice
                val window = sliceWindow(p, slice)
                val idx = (loopStart + p).toInt() and histMask
                l += (histL[idx] * window * STUTTER_GAIN - l) * loopMix
                r += (histR[idx] * window * STUTTER_GAIN - r) * loopMix
            }
        }
        if (current == FX_DJ_DROP) {
            val brakeFrames = shotBeat * 0.5f
            val e = (frame - shotStart).toFloat()
            if (e < brakeFrames) {
                val rate = 1.0 - e / brakeFrames
                val pos = shotStart + brakePos
                val base = floor(pos).toLong()
                val frac = (pos - base).toFloat()
                val a = base.toInt() and histMask
                val b = (base + 1).toInt() and histMask
                l = histL[a] + (histL[b] - histL[a]) * frac
                r = histR[a] + (histR[b] - histR[a]) * frac
                brakePos += rate
            }
        }

        // ---- gate (cuts, drops, build-up roll): slew-limited, no clicks
        gate += (gateTarget - gate).coerceIn(-gateStep, gateStep)
        l *= gate
        r *= gate

        // ---- filters
        if (darkV > 1e-4f) {
            l += (darkL.lp(l) - l) * darkV
            r += (darkR.lp(r) - r) * darkV
        }
        if (hpMixV > 1e-4f) {
            l += (hpL.hp(l) - l) * hpMixV
            r += (hpR.hp(r) - r) * hpMixV
        }
        if (shelfV > 1.0005f) {
            l = shelfL.shelf(l)
            r = shelfR.shelf(r)
            // Low band level before the boost, used to keep the boost below distortion.
            val peak = max(abs(shelfL.low), abs(shelfR.low))
            bassEnv = if (peak > bassEnv) peak else bassEnv * BASS_ENV_RELEASE
        }

        // ---- sidechain duck + synthesized impact
        l *= levelV
        r *= levelV
        if (subStart >= 0) {
            val t = (frame - subStart) / sr.toDouble()
            val freq = SUB_END_HZ + (SUB_START_HZ - SUB_END_HZ) * exp(-t / 0.05)
            subPhase += 2.0 * PI * freq / sr
            if (subPhase > 2.0 * PI) subPhase -= 2.0 * PI
            val env = (1.0 - exp(-t / 0.002)) * exp(-t / subDecay)
            // Saturation adds harmonics so the hit is heard on phone speakers too.
            val s = (tanh(SUB_DRIVE * sin(subPhase)) / tanh(SUB_DRIVE) * env * subAmount).toFloat()
            l += s
            r += s
            if (env < 1e-4 && t > 0.1) subStart = -1
        }
        if (noiseV > 1e-5f) {
            rng = rng xor (rng shl 13)
            rng = rng xor (rng ushr 17)
            rng = rng xor (rng shl 5)
            val white = rng * 4.656613e-10f
            val n = noiseBp.bp(white) * noiseV
            l += n
            r += n
        }

        // ---- echo: tempo-synced, filtered ping-pong feedback, gliding delay time
        if (echoSendV > 1e-5f || echoEnergy > ENERGY_FLOOR) {
            val readPos = echoWrite - echoDelay
            val base = floor(readPos).toInt()
            val frac = readPos - base
            val a = base and echoMask
            val b = (base + 1) and echoMask
            val wetL = echoL[a] + (echoL[b] - echoL[a]) * frac
            val wetR = echoR[a] + (echoR[b] - echoR[a]) * frac
            echoLpL += (wetL - echoLpL) * echoLpCoef
            echoLpR += (wetR - echoLpR) * echoLpCoef
            echoHpL += (echoLpL - echoHpL) * echoHpCoef
            echoHpR += (echoLpR - echoHpR) * echoHpCoef
            val fbL = (echoLpL - echoHpL) * echoFeedback
            val fbR = (echoLpR - echoHpR) * echoFeedback
            val w = echoWrite and echoMask
            echoL[w] = l * echoSendV + fbR
            echoR[w] = r * echoSendV + fbL
            echoWrite++
            echoEnergy = echoEnergy * ENERGY_DECAY + (abs(wetL) + abs(wetR))
            l = l * echoDryV + wetL * echoMixV
            r = r * echoDryV + wetR * echoMixV
        } else {
            echoWrite++
            l *= echoDryV
            r *= echoDryV
        }

        // ---- reverb: 8 combs + 4 allpasses per side, pre-delay, bass kept out
        if (revSendV > 1e-5f || revEnergy > ENERGY_FLOOR) {
            val mono = revInHp.hp((l + r) * 0.5f) * revSendV * REVERB_INPUT
            preL[preWrite and preMask] = mono
            val delayed = preL[(preWrite - (sr / 50)) and preMask]
            preWrite++
            var outL = 0f
            var outR = 0f
            for (c in 0 until 8) {
                outL += combL[c].process(delayed, revFeedback)
                outR += combR[c].process(delayed, revFeedback)
            }
            for (c in 0 until 4) {
                outL = apL[c].process(outL)
                outR = apR[c].process(outR)
            }
            revEnergy = revEnergy * ENERGY_DECAY + (abs(outL) + abs(outR))
            l += outL * revWetV
            r += outR * revWetV
        }

        // ---- limiter: instant attack, smooth release, ceiling -1 dBFS
        val peak = max(abs(l), abs(r))
        if (peak * limiterGain > LIMIT_CEILING) {
            limiterGain = LIMIT_CEILING / peak
        } else {
            limiterGain += (1f - limiterGain) * LIMIT_RELEASE
        }
        l *= limiterGain
        r *= limiterGain

        // ---- crossfade with the untouched signal when entering/leaving processing
        procMix += (if (wantsProcessing) PROC_SLEW else -PROC_SLEW)
        procMix = procMix.coerceIn(0f, 1f)
        val outL = dryL + (l - dryL) * procMix
        val outR = dryR + (r - dryR) * procMix
        if (!wantsProcessing && procMix <= 0f) processing = false

        output.putShort(toPcm(outL))
        if (channels == 2) output.putShort(toPcm(outR))
    }

    private var wantsProcessing = false

    // ================================================================ control

    private fun control(
        enabled: Boolean,
        toggles: Int,
        amounts: FloatArray,
        options: IntArray,
    ) {
        if (enabled) {
            if (engine.primary === this && engine.dropRequest.getAndSet(0) == 1) {
                if (shot >= 0) shot = -1
                pushFront(FX_BASS_DROP)
                startAt = frame
                chained = true
            }
            if (shot >= 0 && frame >= shotStart + shotLength) finishShot()
            if (shot < 0 && queueSize > 0) {
                if (startAt < 0) startAt = if (chained) frame else nextBeat(frame, queue[queueHead])
                if (frame >= startAt) startShot(amounts, options)
            }
            if (transition != 0 && frame >= transitionStart + transitionLength) transition = 0
        } else if (shot >= 0 || queueSize > 0) {
            shot = -1
            queueSize = 0
            startAt = -1
        }

        // ---- targets
        tHpMix = 0f
        tHpHz = 30f
        tHpQ = 0.707f
        tShelfDb = 0f
        tShelfHz = 100f
        tDarkMix = 0f
        tDarkHz = 18000f
        tEchoSend = 0f
        tEchoFeedback = 0.5f
        tEchoMix = 0f
        tEchoDry = 1f
        tRevSend = 0f
        tRevWet = 0f
        tNoise = 0f
        tGain = 1f
        gateTarget = 1f
        val beat = beatFrames()
        tEchoDelay = beat * DjFx.ECHO.options[options[DjFx.ECHO.ordinal]]

        if (toggles and (1 shl DjFx.BASS_BOOST.ordinal) != 0) {
            val a = amounts[DjFx.BASS_BOOST.ordinal]
            tShelfDb = 4f + 8f * a
            tShelfHz = DjFx.BASS_BOOST.options[options[DjFx.BASS_BOOST.ordinal]]
        }
        if (toggles and (1 shl DjFx.ECHO.ordinal) != 0) {
            val a = amounts[DjFx.ECHO.ordinal]
            tEchoSend = 0.7f + 0.3f * a
            tEchoFeedback = 0.4f + 0.3f * a
            tEchoMix = 0.55f + 0.35f * a
        }
        if (toggles and (1 shl DjFx.REVERB.ordinal) != 0) {
            val a = amounts[DjFx.REVERB.ordinal]
            tRevSend = 1f
            tRevWet = 0.9f + 1.8f * a
            revFeedback = DjFx.REVERB.options[options[DjFx.REVERB.ordinal]]
        } else {
            revFeedback = DjFx.REVERB.options[1]
        }

        if (shot >= 0) shotTargets(beat)
        if (transition != 0) transitionTargets()

        if (shelfBurstStart >= 0) {
            val p = (frame - shelfBurstStart).toFloat() / shelfBurstLength
            if (p >= 1f) {
                shelfBurstStart = -1
            } else {
                tShelfDb = max(tShelfDb, shelfBurstDb * (1f - p))
                tShelfHz = max(tShelfHz, 100f)
            }
        }
        duck =
            if (duckStart >= 0) {
                val e = (frame - duckStart) / duckBeat
                if (e > 2f) {
                    duckStart = -1
                    1f
                } else {
                    1f - duckDepth * exp(-e * 3f)
                }
            } else {
                1f
            }

        // ---- smoothing (one pole per control block)
        hpMix.toward(tHpMix, SMOOTH)
        hpHz += (tHpHz - hpHz) * SMOOTH_FAST
        hpQ += (tHpQ - hpQ) * SMOOTH
        if (hpMix.active(1e-4f)) {
            hpL.set(sr, hpHz, hpQ)
            hpR.copyFrom(hpL)
        } else {
            hpL.clear()
            hpR.clear()
        }
        val targetGain = dbToGain(tShelfDb)
        shelfGain.toward(targetGain, SMOOTH)
        if (shelfGain.end > 1.0005f || shelfGain.cur > 1.0005f) {
            // Bass limiter: the boosted low band may not exceed the ceiling, so the boost
            // backs off on loud bass instead of clipping.
            val allowed = if (bassEnv > 1e-4f) max(1f, BASS_CEILING / bassEnv) else shelfGain.end
            bassLimit += (min(shelfGain.end, allowed) - bassLimit) * 0.3f
            shelfShelfHz += (tShelfHz - shelfShelfHz) * SMOOTH_FAST
            shelfL.setLowShelf(sr, shelfShelfHz, 0.707f, max(1f, bassLimit))
            shelfR.copyFrom(shelfL)
        } else {
            bassLimit = 1f
            bassEnv = 0f
            shelfL.clear()
            shelfR.clear()
        }
        darkMix.toward(tDarkMix, SMOOTH_FAST)
        darkHz += (tDarkHz - darkHz) * SMOOTH_FAST
        if (darkMix.active(1e-4f)) {
            darkL.set(sr, darkHz, 0.9f)
            darkR.copyFrom(darkL)
        }
        echoSend.toward(tEchoSend, SMOOTH)
        echoFeedback += (tEchoFeedback - echoFeedback) * SMOOTH
        echoMix.toward(tEchoMix, SMOOTH)
        echoDry.toward(tEchoDry, SMOOTH_FAST)
        echoDelay = if (echoDelay <= 0f) tEchoDelay else echoDelay + (tEchoDelay - echoDelay) * DELAY_GLIDE
        echoDelay = echoDelay.coerceIn(1f, (echoMask - 2).toFloat())
        revSend.toward(tRevSend, SMOOTH)
        revWet.toward(tRevWet, SMOOTH)
        noiseLevel.toward(tNoise, SMOOTH)
        gain += (tGain - gain) * SMOOTH
        level.toward(gain * duck, 1f)

        val neutral =
            shot < 0 && queueSize == 0 && transition == 0 && subStart < 0 && duckStart < 0 &&
                shelfBurstStart < 0 && toggles == 0 &&
                gate >= 0.9999f && gateTarget >= 1f && loopMix <= 0f &&
                !hpMix.active(1e-4f) && !shelfGain.active(0.0005f, above = 1f) && !darkMix.active(1e-4f) &&
                !echoSend.active(1e-5f) && echoEnergy < ENERGY_FLOOR && !revSend.active(1e-5f) && revEnergy < ENERGY_FLOOR &&
                !noiseLevel.active(1e-5f) && !level.active(1e-4f, above = 1f) && !echoDry.active(1e-4f, above = 1f)
        wantsProcessing = !neutral
        if (wantsProcessing && !processing) {
            processing = true
            limiterGain = 1f
        }
    }

    private fun shotTargets(beat: Float) {
        val e = (frame - shotStart).toFloat()
        val p = (e / shotLength).coerceIn(0f, 1f)
        val a = shotAmount
        when (shot) {
            FX_STUTTER -> {
                // Second half of the roll: rising filter for tension.
                if (p > 0.5f) {
                    val q = (p - 0.5f) * 2f
                    tHpMix = 1f
                    tHpHz = 30f * ((200f + 1400f * a) / 30f).pow(q)
                    tHpQ = 0.8f + 1.2f * a * q
                }
            }
            FX_QUICK_CUTS -> {
                val step = (e / (shotBeat / 4f)).toInt() and 15
                val open = CUT_PATTERNS[cutPattern][step]
                val depth = (0.5f + a * 0.75f).coerceAtMost(1f)
                gateTarget = if (open) 1f else 1f - depth
            }
            FX_DJ_DROP -> {
                val brake = shotBeat * 0.5f
                val silence = shotBeat * shotOption
                when {
                    e < brake -> {
                        val q = e / brake
                        tDarkMix = 1f
                        tDarkHz = 16000f * (500f / 16000f).pow(q)
                        tGain = 1f - 0.25f * q * q
                    }
                    e < brake + silence -> gateTarget = 0f
                    else -> Unit
                }
                if (e >= brake + silence && subStart < 0 && shelfBurstStart < 0) impact(0.7f + 0.3f * a, 2f)
            }
            FX_BASS_DROP -> Unit // the impact is fired once in startShot
            FX_BUILD_UP -> {
                val beats = shotLength / shotBeat
                val beatPos = e / shotBeat
                tHpMix = 1f
                tHpHz = 30f * ((600f + 2400f * a) / 30f).pow(p)
                tHpQ = 0.707f + 2.3f * a * p
                noiseBp.set(sr, 400f * 20f.pow(p), 1.4f)
                tNoise = (0.02f + 0.16f * a) * p * p
                tRevSend = max(tRevSend, p * a)
                tRevWet = max(tRevWet, 1.5f * p * a)
                tGain = 1f + 0.25f * a * p
                gateTarget =
                    when {
                        beatPos > beats - 0.125f -> 0f // pre-drop gap
                        beatPos > beats - 1f -> rollGate(e, shotBeat / 8f, 0.85f * a)
                        beatPos > beats - 2f -> rollGate(e, shotBeat / 4f, 0.7f * a)
                        beatPos > beats - 3f -> rollGate(e, shotBeat / 2f, 0.5f * a)
                        else -> 1f
                    }
            }
            FX_ECHO_OUT -> {
                tEchoSend = if (e < shotBeat) 1f else 0f
                tEchoFeedback = 0.55f + 0.2f * a
                tEchoMix = 0.8f + 0.2f * a
                tEchoDelay = beat * 0.75f
                tEchoDry = if (e < shotBeat || p > 0.94f) 1f else 0f
                tRevSend = max(tRevSend, 0.5f * a)
                tRevWet = max(tRevWet, 1.2f * a)
            }
        }
    }

    private fun transitionTargets() {
        val p = ((frame - transitionStart).toFloat() / transitionLength).coerceIn(0f, 1f)
        if (transition == TRANSITION_OUT) {
            tHpMix = 1f
            tHpHz = max(tHpHz, 30f * (1200f / 30f).pow(p))
            if (p > 0.25f) {
                tEchoSend = max(tEchoSend, 1f)
                tEchoFeedback = max(tEchoFeedback, 0.62f)
                tEchoMix = max(tEchoMix, 0.9f)
                tEchoDelay = beatFrames() * 0.75f
            }
            tRevSend = max(tRevSend, 0.6f * p)
            tRevWet = max(tRevWet, 1.5f * p)
        } else {
            // Bass swap: the incoming bass waits, then comes in over half a beat.
            if (p < 0.6f) {
                tHpMix = 1f
                tHpHz = max(tHpHz, 220f)
            }
        }
    }

    private fun rollGate(
        e: Float,
        step: Float,
        depth: Float,
    ): Float = if (((e / step).toInt() and 1) == 0) 1f else 1f - depth

    // ================================================================= shots

    private fun pollCommands() {
        while (true) {
            val code = engine.pendingShots.poll() ?: break
            val fx = code and 0xFF
            if (code and DjFxEngine.APPEND != 0) {
                pushBack(fx)
            } else {
                // A pad press interrupts: drop what is queued and what is running.
                queueSize = 0
                shot = -1
                startAt = -1
                chained = false
                pushBack(fx)
            }
        }
    }

    private fun pushBack(fx: Int) {
        if (queueSize == queue.size) return
        queue[(queueHead + queueSize) % queue.size] = fx
        queueSize++
    }

    private fun pushFront(fx: Int) {
        if (queueSize == queue.size) queueSize--
        queueHead = (queueHead - 1 + queue.size) % queue.size
        queue[queueHead] = fx
        queueSize++
    }

    private fun startShot(
        amounts: FloatArray,
        options: IntArray,
    ) {
        val fx = queue[queueHead]
        queueHead = (queueHead + 1) % queue.size
        queueSize--
        startAt = -1
        chained = false

        val entry = DjFx.entries[fx]
        shot = fx
        shotStart = frame
        shotBeat = beatFrames()
        shotAmount = amounts[if (fx == FX_ECHO_OUT) DjFx.ECHO.ordinal else fx]
        shotOption = entry.options[options[fx]]
        val beats =
            when (fx) {
                FX_DJ_DROP -> 0.5f + shotOption + 2f
                else -> shotOption
            }
        shotLength = (beats * shotBeat).toLong().coerceAtLeast(1)

        when (fx) {
            FX_STUTTER -> {
                // Loop the most recent beat that is fully in the history.
                val slice = (shotBeat / 4f).toLong()
                var start = lastBeat(frame)
                if (frame - start < slice) start -= shotBeat.toLong()
                if (start < 0 || frame - start > histMask) start = frame - slice
                loopStart = start
            }
            FX_DJ_DROP -> brakePos = 0.0
            FX_BASS_DROP -> impact(0.75f + 0.25f * shotAmount, shotOption)
            FX_QUICK_CUTS -> {
                rng = rng * 1103515245 + 12345
                cutPattern = (rng ushr 16) % CUT_PATTERNS.size
            }
        }
    }

    private fun finishShot() {
        val finished = shot
        shot = -1
        chained = queueSize > 0
        val dropAlreadyQueued = queueSize > 0 && queue[queueHead] == FX_BASS_DROP
        // The drop goes to whichever player is audible by now (it may be the next track).
        if (finished == FX_BUILD_UP && !dropAlreadyQueued) engine.dropRequest.set(1)
    }

    /** Synthesized 808-style hit, a bass boost burst and a short sidechain duck. */
    private fun impact(
        strength: Float,
        beats: Float,
    ) {
        subStart = frame
        subPhase = 0.0
        subAmount = SUB_LEVEL * strength
        subDecay = (0.35 * beats * shotBeat / sr).coerceIn(0.25, 2.5).toFloat()
        shelfBurstStart = frame
        shelfBurstLength = (beats * shotBeat).toLong().coerceAtLeast(1)
        shelfBurstDb = 6f + 6f * strength
        duckStart = frame
        duckDepth = 0.55f * strength
        duckBeat = shotBeat
    }

    private fun stutterSlice(e: Int): Int {
        val half = shotLength / 2
        val threeQuarter = shotLength * 3 / 4
        val div =
            when {
                e < half -> 4f
                e < threeQuarter -> 8f
                else -> 16f
            }
        return (shotBeat / div).toInt().coerceAtLeast(64)
    }

    private fun sliceWindow(
        p: Int,
        slice: Int,
    ): Float {
        val ramp = min((sr * 0.0015f).toInt(), slice / 4).coerceAtLeast(1)
        return when {
            p < ramp -> 0.5f - 0.5f * kotlin.math.cos(PI.toFloat() * p / ramp)
            p >= slice - ramp -> 0.5f - 0.5f * kotlin.math.cos(PI.toFloat() * (slice - p) / ramp)
            else -> 1f
        }
    }

    private fun consumeTransitionRequest() {
        val request = transitionRequest
        if (request == 0) return
        transitionRequest = 0
        transition = request
        transitionStart = frame
        transitionLength = (transitionRequestMs * sr / 1000L).coerceAtLeast(1L)
    }

    // ================================================================= tempo

    private fun beatFrames(): Float = sr * 60f / (if (tempoLocked) bpm else DjFxEngine.DEFAULT_BPM)

    private fun lastBeat(now: Long): Long {
        if (!tempoLocked) return now
        val b = beatFrames()
        val n = floor((now - beatAnchor) / b.toDouble())
        return beatAnchor + (n * b).toLong()
    }

    private fun nextBeat(
        now: Long,
        fx: Int,
    ): Long {
        if (!tempoLocked || fx == FX_STUTTER) return now
        val b = beatFrames()
        val n = ceil((now - beatAnchor) / b.toDouble())
        return beatAnchor + (n * b).toLong()
    }

    private fun analyzeOnset(mono: Float) {
        analysisLp += (mono - analysisLp) * analysisAlpha
        hopAcc += analysisLp * analysisLp
        if (++hopCount < hopFrames) return
        val envelope = sqrt(hopAcc / hopCount)
        hopAcc = 0f
        hopCount = 0
        val written = hopsWritten
        onsetRing[(written and RING_MASK.toLong()).toInt()] = (envelope - prevEnvelope).coerceAtLeast(0f)
        prevEnvelope = envelope
        hopsWritten = written + 1
    }

    /**
     * Estimates tempo and beat phase from the onset history. Runs on a background thread;
     * the render path only reads the volatile results.
     */
    fun runTempoAnalysis() {
        if (!isFormatSupported) return
        val epoch = tempoEpoch
        val written = hopsWritten
        val n = min(written, ONSET_WINDOW.toLong()).toInt()
        if (n < MIN_ANALYSIS_HOPS) return
        for (i in 0 until n) scratch[i] = onsetRing[((written - n + i) and RING_MASK.toLong()).toInt()]

        var energy = 0f
        for (i in 0 until n) energy += scratch[i] * scratch[i]
        if (energy <= 1e-9f) return

        var bestLag = 0
        var bestScore = 0f
        for (lag in MIN_LAG..MAX_LAG) {
            var score = 0f
            for (i in lag until n) score += scratch[i] * scratch[i - lag]
            val lagBpm = 60f * HOPS_PER_SECOND / lag
            val octave = ln(lagBpm / 120f) / LN2 / 0.6f
            score *= exp(-0.5f * octave * octave)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag == 0 || bestScore / energy < MIN_CONFIDENCE) return

        var bestOffset = 0
        var bestPhase = -1f
        for (offset in 0 until bestLag) {
            var sum = 0f
            var k = n - 1 - offset
            while (k >= 0) {
                sum += scratch[k]
                k -= bestLag
            }
            if (sum > bestPhase) {
                bestPhase = sum
                bestOffset = offset
            }
        }
        if (epoch != tempoEpoch) return

        val newBpm = 60f * HOPS_PER_SECOND / bestLag
        val smoothed = if (tempoLocked && abs(newBpm - bpm) < 4f) bpm * 0.7f + newBpm * 0.3f else newBpm
        beatAnchor = (written - 1 - bestOffset) * hopFrames
        bpm = smoothed
        tempoLocked = true
        engine.publishTempo(this, smoothed)
    }

    // ================================================================= DSP

    /** A control-rate value interpolated linearly per sample across each control block. */
    private class Ramp(initial: Float) {
        var cur = initial
        var end = initial
        private var inc = 0f

        fun set(v: Float) {
            cur = v
            end = v
            inc = 0f
        }

        fun toward(
            target: Float,
            coef: Float,
        ) {
            cur = end
            end += (target - end) * coef
            inc = (end - cur) / (CONTROL_MASK + 1)
        }

        fun step(): Float {
            cur += inc
            return cur
        }

        /** True while the value (or where it is heading) is away from [above] by more than [eps]. */
        fun active(
            eps: Float,
            above: Float = 0f,
        ): Boolean = abs(cur - above) > eps || abs(end - above) > eps
    }

    /** Zero-delay-feedback state variable filter (Zavalishin / Simper). */
    private class Svf {
        private var a1 = 0f
        private var a2 = 0f
        private var a3 = 0f
        private var k = 1.414f
        private var ic1 = 0f
        private var ic2 = 0f
        private var lastHp = 0f
        private var lastBp = 0f
        private var m1 = 0f
        private var m2 = 0f

        /** Low-pass state of the last sample (the band a low shelf boosts). */
        var low = 0f
            private set

        fun set(
            sampleRate: Int,
            hz: Float,
            q: Float,
        ) {
            val f = hz.coerceIn(10f, sampleRate * 0.45f)
            val g = tan(PI * f / sampleRate).toFloat()
            k = 1f / q.coerceAtLeast(0.1f)
            a1 = 1f / (1f + g * (g + k))
            a2 = g * a1
            a3 = g * a2
        }

        /** Simper low shelf: DC gain [gain] (linear), unity above [hz]. */
        fun setLowShelf(
            sampleRate: Int,
            hz: Float,
            q: Float,
            gain: Float,
        ) {
            val a = sqrt(gain)
            val f = hz.coerceIn(10f, sampleRate * 0.45f)
            val g = (tan(PI * f / sampleRate) / sqrt(a.toDouble())).toFloat()
            k = 1f / q
            a1 = 1f / (1f + g * (g + k))
            a2 = g * a1
            a3 = g * a2
            m1 = k * (a - 1f)
            m2 = a * a - 1f
        }

        fun shelf(x: Float): Float {
            tick(x)
            return x + m1 * lastBp + m2 * low
        }

        fun copyFrom(other: Svf) {
            m1 = other.m1
            m2 = other.m2
            a1 = other.a1
            a2 = other.a2
            a3 = other.a3
            k = other.k
        }

        fun clear() {
            ic1 = 0f
            ic2 = 0f
        }

        private fun tick(v0: Float): Float {
            val v3 = v0 - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2f * v1 - ic1
            ic2 = 2f * v2 - ic2
            if (abs(ic1) < 1e-25f) ic1 = 0f
            if (abs(ic2) < 1e-25f) ic2 = 0f
            lastBp = v1
            low = v2
            lastHp = v0 - k * v1 - v2
            return v2
        }

        fun lp(x: Float): Float = tick(x)

        fun hp(x: Float): Float {
            tick(x)
            return lastHp
        }

        fun bp(x: Float): Float {
            tick(x)
            return lastBp
        }
    }

    private class Comb {
        private var buffer = FloatArray(1)
        private var length = 1
        private var index = 0
        private var store = 0f

        fun ensure(size: Int) {
            val s = size.coerceAtLeast(1)
            if (buffer.size < s) buffer = FloatArray(s)
            length = s
            index = 0
        }

        fun clear() {
            buffer.fill(0f)
            store = 0f
            index = 0
        }

        fun process(
            input: Float,
            feedback: Float,
        ): Float {
            val out = buffer[index]
            store = out * (1f - REVERB_DAMP) + store * REVERB_DAMP
            if (abs(store) < 1e-25f) store = 0f
            buffer[index] = input + store * feedback
            if (++index >= length) index = 0
            return out
        }
    }

    private class Allpass {
        private var buffer = FloatArray(1)
        private var length = 1
        private var index = 0

        fun ensure(size: Int) {
            val s = size.coerceAtLeast(1)
            if (buffer.size < s) buffer = FloatArray(s)
            length = s
            index = 0
        }

        fun clear() {
            buffer.fill(0f)
            index = 0
        }

        fun process(input: Float): Float {
            val buffered = buffer[index]
            val out = buffered - input
            buffer[index] = input + buffered * 0.5f
            if (++index >= length) index = 0
            return out
        }
    }

    private fun toPcm(x: Float): Short {
        // Safety net after the limiter; only reached on sub-millisecond overshoots.
        val y = if (abs(x) > 0.98f) (if (x > 0) 1f else -1f) * (0.98f + 0.02f * tanh((abs(x) - 0.98f) / 0.02f)) else x
        return (y * 32768f).toInt().coerceIn(-32768, 32767).toShort()
    }

    private fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

    private fun nextPow2(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    private companion object {
        val FX_BASS_DROP = DjFx.BASS_DROP.ordinal
        val FX_STUTTER = DjFx.STUTTER.ordinal
        val FX_BUILD_UP = DjFx.BUILD_UP.ordinal
        val FX_DJ_DROP = DjFx.DJ_DROP.ordinal
        val FX_QUICK_CUTS = DjFx.QUICK_CUTS.ordinal
        val FX_ECHO_OUT = DjFx.ECHO_OUT.ordinal

        const val TRANSITION_OUT = 1
        const val TRANSITION_IN = 2

        const val CONTROL_MASK = 31
        const val HISTORY_SECONDS = 4
        const val ECHO_SECONDS = 3

        const val HOPS_PER_SECOND = 100
        const val ONSET_RING = 1024
        const val RING_MASK = ONSET_RING - 1
        const val ONSET_WINDOW = 800
        const val MIN_ANALYSIS_HOPS = 500
        const val MIN_LAG = 33
        const val MAX_LAG = 80
        const val MIN_CONFIDENCE = 0.08f
        val LN2 = ln(2f)

        const val SMOOTH = 0.08f
        const val SMOOTH_FAST = 0.25f
        const val DELAY_GLIDE = 0.02f
        const val PROC_SLEW = 1f / 480f
        const val LOOP_SLEW = 1f / 240f
        const val GATE_RAMP_SECONDS = 0.0015f

        const val STUTTER_GAIN = 1.12f
        const val BASS_CEILING = 0.72f
        const val BASS_ENV_RELEASE = 0.9995f

        const val SUB_START_HZ = 110.0
        const val SUB_END_HZ = 45.0
        const val SUB_DRIVE = 1.8
        const val SUB_LEVEL = 0.5f

        const val LIMIT_CEILING = 0.89f
        const val LIMIT_RELEASE = 0.0004f

        const val ENERGY_FLOOR = 0.002f
        const val ENERGY_DECAY = 0.999f

        const val REVERB_INPUT = 0.015f
        const val REVERB_DAMP = 0.3f
        const val STEREO_SPREAD = 23
        val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val ALLPASS_TUNING = intArrayOf(556, 441, 341, 225)

        val CUT_PATTERNS =
            arrayOf(
                booleanArrayOf(true, true, false, true, true, false, true, false, true, true, false, true, false, true, true, false),
                booleanArrayOf(true, false, true, false, true, false, true, false, true, false, true, false, true, true, true, false),
                booleanArrayOf(true, true, true, false, true, true, false, false, true, true, true, false, true, false, true, false),
            )
    }
}
