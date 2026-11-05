/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Feeds a synthetic 120 BPM track (kick drum + hi-hat + pad) through the processor and
 * measures the PCM that comes out, so every effect is proven to change the audio.
 */
class DjFxAudioProcessorTest {
    private val engine = DjFxEngine()
    private val processor = DjFxAudioProcessor(engine)

    private fun configure(format: AudioProcessor.AudioFormat = FORMAT): AudioProcessor.AudioFormat {
        val out = processor.configure(format)
        @Suppress("DEPRECATION")
        processor.flush()
        return out
    }

    @Test
    fun `disabled engine passes audio through bit for bit`() {
        configure()
        val input = track(seconds = 3f)
        val output = run(input)
        assertArrayEquals(input, output)
    }

    @Test
    fun `enabled engine with no effect active stays bit exact`() {
        configure()
        enable()
        val input = track(seconds = 3f)
        val output = run(input)
        assertArrayEquals(input, output)
    }

    @Test
    fun `tempo detector locks onto 120 bpm`() {
        configure()
        enable()
        run(track(seconds = 12f))
        assertEquals(120f, processor.lockedBpm, 3f)
        assertEquals(processor.lockedBpm, engine.bpm.value, 0.01f)
    }

    @Test
    fun `bass boost raises low frequencies`() {
        configure()
        enable()
        engine.toggle(DjFx.BASS_BOOST)
        val input = track(seconds = 4f)
        val output = run(input)
        val from = SR * 2
        val to = SR * 4
        val ratio = tone(output, 55.0, from, to) / tone(input, 55.0, from, to)
        assertTrue("55 Hz gain ratio was $ratio", ratio > 1.8)
        val highRatio = tone(output, 6000.0, from, to) / tone(input, 6000.0, from, to)
        assertEquals("highs should stay put", 1.0, highRatio, 0.15)
    }

    @Test
    fun `dj drop silences the music for a beat then brings it back`() {
        configure()
        enable()
        val input = track(seconds = 14f)
        val output = run(input, triggerAt = SR * 10, fx = DjFx.DJ_DROP)
        val silentWindow = findQuietWindow(output, SR * 10, SR * 12, windowFrames = SR / 4)
        assertTrue("no silent quarter second after DJ drop", silentWindow >= 0)
        assertTrue("music did not come back", rms(output, SR * 12, SR * 13) > 0.05)
    }

    @Test
    fun `quick cuts gate the music in sixteenth notes`() {
        configure()
        enable()
        val input = track(seconds = 16f)
        val output = run(input, triggerAt = SR * 10, fx = DjFx.QUICK_CUTS)
        val step = SR / 8 // 1/16 at 120 BPM
        var silent = 0
        var loud = 0
        var start = SR * 10
        while (start + step <= SR * 15) {
            val level = rms(output, start + step / 4, start + 3 * step / 4)
            if (level < 0.01) silent++
            if (level > 0.05) loud++
            start += step / 2
        }
        assertTrue("silent=$silent loud=$loud", silent >= 6 && loud >= 6)
    }

    @Test
    fun `beat stutter repeats a captured slice`() {
        configure()
        enable()
        val input = track(seconds = 14f)
        val output = run(input, triggerAt = SR * 10, fx = DjFx.STUTTER)
        val changed = differingFrames(input, output, SR * 10, SR * 13)
        assertTrue("stutter changed only $changed frames", changed > SR / 2)

        // During the 1/8 roll each 1/16 slice is played twice, so the output repeats
        // itself one slice later far more often than the original music does.
        val slice = SR / 8
        val repeatedOut = repeatFraction(output, SR * 10, SR * 12, slice)
        val repeatedIn = repeatFraction(input, SR * 10, SR * 12, slice)
        assertTrue("output repeat $repeatedOut vs input $repeatedIn", repeatedOut > 0.3 && repeatedIn < 0.1)
    }

    @Test
    fun `echo keeps ringing after the input stops`() {
        configure()
        enable()
        engine.toggle(DjFx.ECHO)
        val input = track(seconds = 3f) + ShortArray(SR * 2 * CH)
        val output = run(input)
        assertTrue(rms(output, SR * 3 + SR / 10, SR * 4) > 0.01)
    }

    @Test
    fun `reverb adds a tail after the input stops`() {
        configure()
        enable()
        engine.toggle(DjFx.REVERB)
        val input = track(seconds = 3f) + ShortArray(SR * 2 * CH)
        val output = run(input)
        assertTrue(rms(output, SR * 3 + SR / 20, SR * 3 + SR / 2) > 0.003)
    }

    @Test
    fun `no tail without echo or reverb`() {
        configure()
        enable()
        val input = track(seconds = 3f) + ShortArray(SR * 2 * CH)
        val output = run(input)
        assertEquals(0.0, rms(output, SR * 3 + SR / 10, SR * 4), 1e-9)
    }

    @Test
    fun `build up filters out the bass and resolves into a bass drop`() {
        configure()
        enable()
        val input = track(seconds = 20f)
        val output = run(input, triggerAt = SR * 10, fx = DjFx.BUILD_UP)
        // Third beat of the build-up from its start (quantized within one beat).
        val lateFrom = SR * 10 + SR * 3 / 2 + SR / 2
        val lateTo = lateFrom + SR
        val bassCut = tone(output, 55.0, lateFrom, lateTo) / tone(input, 55.0, lateFrom, lateTo)
        assertTrue("bass during build-up ratio $bassCut", bassCut < 0.7)
        // The drop that follows adds sub energy around 40-70 Hz.
        val dropFrom = SR * 14 + SR / 2
        val dropTo = dropFrom + SR
        val sub = tone(output, 45.0, dropFrom, dropTo) / tone(input, 45.0, dropFrom, dropTo)
        assertTrue("sub after build-up ratio $sub", sub > 1.5)
    }

    @Test
    fun `incoming transition holds the bass back at first`() {
        configure()
        enable()
        processor.requestTransitionIn(4_000)
        val input = track(seconds = 6f)
        val output = run(input)
        val early = tone(output, 55.0, SR / 2, SR) / tone(input, 55.0, SR / 2, SR)
        assertTrue("bass at start of transition ratio $early", early < 0.5)
        val late = tone(output, 55.0, SR * 5, SR * 6) / tone(input, 55.0, SR * 5, SR * 6)
        assertEquals("bass restored after transition", 1.0, late, 0.1)
    }

    @Test
    fun `unsupported formats are bypassed and reported`() {
        engine.primary = processor
        val out = configure(AudioProcessor.AudioFormat(SR, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, out)
        assertFalse(processor.isActive)
        assertFalse(engine.ready.value)
    }

    // ---------------------------------------------------------------- helpers

    private fun enable() {
        engine.enabled = true
        engine.onPrimaryChanged(processor)
    }

    /** Interleaved stereo 16-bit: kick every 0.5 s, hi-hat on off-beats, a soft 6 kHz pad. */
    private fun track(seconds: Float): ShortArray {
        val frames = (SR * seconds).toInt()
        val out = ShortArray(frames * CH)
        val beat = SR / 2
        for (n in 0 until frames) {
            val inBeat = n % beat
            val t = inBeat / SR.toDouble()
            val kick = if (inBeat < SR / 5) 0.2 * sin(2 * PI * 55.0 * t) * exp(-t * 12) else 0.0
            val bass = 0.08 * sin(2 * PI * 55.0 * n / SR)
            val hatPhase = (n + beat / 2) % beat
            val hat = if (hatPhase < SR / 50) 0.05 * sin(2 * PI * 9000.0 * n / SR) * (1 - hatPhase / (SR / 50.0)) else 0.0
            val pad = 0.06 * sin(2 * PI * 6000.0 * n / SR)
            val s = ((kick + bass + hat + pad) * 32767).toInt().toShort()
            out[n * 2] = s
            out[n * 2 + 1] = s
        }
        return out
    }

    private fun run(
        input: ShortArray,
        triggerAt: Int = -1,
        fx: DjFx? = null,
    ): ShortArray {
        val out = ShortArray(input.size)
        var written = 0
        var frame = 0
        val totalFrames = input.size / CH
        while (frame < totalFrames) {
            val count = minOf(CHUNK, totalFrames - frame)
            if (fx != null && triggerAt in frame until frame + count) engine.pendingShots.add(fx)
            val buffer = ByteBuffer.allocateDirect(count * CH * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until count * CH) buffer.putShort(input[frame * CH + i])
            buffer.flip()
            processor.queueInput(buffer)
            val result = processor.output
            while (result.hasRemaining()) out[written++] = result.getShort()
            frame += count
        }
        return out
    }

    private fun left(
        pcm: ShortArray,
        frame: Int,
    ) = pcm[frame * CH] / 32768.0

    private fun rms(
        pcm: ShortArray,
        from: Int,
        to: Int,
    ): Double {
        var sum = 0.0
        for (f in from until to) sum += left(pcm, f).let { it * it }
        return sqrt(sum / (to - from))
    }

    /** Goertzel magnitude of one frequency on the left channel. */
    private fun tone(
        pcm: ShortArray,
        freq: Double,
        from: Int,
        to: Int,
    ): Double {
        val coeff = 2 * cos(2 * PI * freq / SR)
        var s1 = 0.0
        var s2 = 0.0
        for (f in from until to) {
            val s = left(pcm, f) + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        return sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2)
    }

    private fun findQuietWindow(
        pcm: ShortArray,
        from: Int,
        to: Int,
        windowFrames: Int,
    ): Int {
        var start = from
        while (start + windowFrames <= to) {
            if (rms(pcm, start, start + windowFrames) < 0.002) return start
            start += windowFrames / 8
        }
        return -1
    }

    private fun differingFrames(
        a: ShortArray,
        b: ShortArray,
        from: Int,
        to: Int,
    ): Int = (from until to).count { abs(a[it * CH] - b[it * CH]) > 64 }

    private fun repeatFraction(
        pcm: ShortArray,
        from: Int,
        to: Int,
        lag: Int,
    ): Double = (from until to).count { abs(pcm[it * CH] - pcm[(it - lag) * CH]) <= 32 } / (to - from).toDouble()

    private companion object {
        const val SR = 44_100
        const val CH = 2
        const val CHUNK = 1024
        val FORMAT = AudioProcessor.AudioFormat(SR, CH, C.ENCODING_PCM_16BIT)
    }
}
