/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time safety of the DJ effects: no allocation on the render path, CPU cost,
 * click-free switching, crossfades with effects running, combos and presets.
 */
class DjFxRealtimeTest {
    @Test
    fun `render path does not allocate with every effect running`() {
        val engine = DjFxEngine()
        val processor = newProcessor(engine)
        val input = music(seconds = 24f)
        val buffers = chunks(input)
        val out = ShortArray(input.size)
        // Warm up: the first buffer may size the output buffer once.
        feed(processor, buffers.take(4), out, 0)
        lockTempo(processor, buffers, out)

        DjFx.entries.filter { !it.oneShot }.forEach(engine::toggle)
        listOf(DjFx.BUILD_UP, DjFx.BASS_DROP, DjFx.ECHO_OUT, DjFx.STUTTER, DjFx.QUICK_CUTS, DjFx.DJ_DROP)
            .forEachIndexed { i, fx -> engine.enqueue(fx, append = i > 0) }

        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(thread)
        var written = 0
        for (buffer in buffers) {
            buffer.rewind()
            processor.queueInput(buffer)
            val result = processor.output
            while (result.hasRemaining() && written < out.size) out[written++] = result.getShort()
        }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        println("DJFX allocation on render path: $allocated bytes for ${buffers.size} buffers")
        assertTrue("render path allocated $allocated bytes", allocated < 2_048)
    }

    @Test
    fun `processing cost stays far below real time`() {
        val engine = DjFxEngine()
        val processor = newProcessor(engine)
        val seconds = 30f
        val input = music(seconds)
        val buffers = chunks(input)
        val out = ShortArray(input.size)
        lockTempo(processor, buffers, out)

        DjFx.entries.filter { !it.oneShot }.forEach(engine::toggle)
        repeat(3) {
            listOf(DjFx.BUILD_UP, DjFx.BASS_DROP, DjFx.ECHO_OUT, DjFx.STUTTER, DjFx.QUICK_CUTS, DjFx.DJ_DROP)
                .forEachIndexed { i, fx -> engine.enqueue(fx, append = i > 0) }
        }
        // Warm the JIT, then measure.
        feed(processor, buffers, out, 0)
        val start = System.nanoTime()
        feed(processor, buffers, out, 0)
        val elapsed = (System.nanoTime() - start) / 1e9
        val load = elapsed / seconds
        println("DJFX CPU: ${"%.4f".format(load * 100)}% of one core for all effects (${"%.1f".format(1 / load)}x real time)")
        assertTrue("load $load", load < 0.05)
    }

    @Test
    fun `switching effects on and off never clicks`() {
        val engine = DjFxEngine()
        val processor = newProcessor(engine)
        val input = sine(seconds = 24f, hz = 440.0, amp = 0.25)
        val events =
            listOf<Pair<Double, () -> Unit>>(
                1.37 to { engine.toggle(DjFx.ECHO) },
                2.11 to { engine.toggle(DjFx.BASS_BOOST) },
                3.03 to { engine.toggle(DjFx.REVERB) },
                4.21 to { engine.enqueue(DjFx.STUTTER) },
                5.02 to { engine.toggle(DjFx.ECHO) },
                7.13 to { engine.enqueue(DjFx.QUICK_CUTS) },
                9.07 to { engine.enqueue(DjFx.STUTTER) },
                9.61 to { engine.enqueue(DjFx.DJ_DROP) },
                13.4 to { engine.enqueue(DjFx.BASS_DROP) },
                15.9 to { engine.toggle(DjFx.BASS_BOOST) },
                16.3 to { engine.toggle(DjFx.REVERB) },
                17.1 to { engine.enqueue(DjFx.ECHO_OUT) },
                19.7 to { engine.toggle(DjFx.ECHO) },
                20.2 to { engine.toggle(DjFx.ECHO) },
            )
        val output = runWithEvents(processor, input, events)
        val natural = 2 * PI * 440.0 / SR * 0.25
        val worst = maxStep(output, SR / 2, output.size / CH)
        println("DJFX worst sample step: $worst (sine alone: $natural)")
        // A click is a jump of a large part of full scale; effects stay within a few steps of a sine.
        assertTrue("largest sample-to-sample jump $worst", worst < 0.12)
    }

    @Test
    fun `crossfade with effects running hands the drop to the incoming track`() {
        val engine = DjFxEngine()
        val outgoing = newProcessor(engine)
        val incoming = newProcessor(engine)
        engine.onPrimaryChanged(outgoing)
        val a = sine(seconds = 10f, hz = 330.0, amp = 0.3)
        val b = sine(seconds = 10f, hz = 523.0, amp = 0.3)
        val outA = ShortArray(a.size)
        val outB = ShortArray(b.size)
        engine.enqueue(DjFx.BUILD_UP)
        var frame = 0
        val total = a.size / CH
        var swapped = false
        while (frame < total) {
            val count = minOf(CHUNK, total - frame)
            if (!swapped && frame >= SR * 3) {
                swapped = true
                engine.onPrimaryChanged(incoming)
                outgoing.requestTransitionOut(6_000)
                incoming.requestTransitionIn(6_000)
            }
            process(outgoing, a, outA, frame, count)
            if (swapped) process(incoming, b, outB, frame, count) else copy(b, outB, frame, count)
            frame += count
        }
        // The outgoing track carries the build-up's noise riser until ~4 s, which is
        // legitimately bright; check it for clicks from there on (transition-out, echoes).
        val clickA = maxStep(outA, SR * 43 / 10, total)
        val clickB = maxStep(outB, SR / 2, total)
        assertTrue("outgoing jump $clickA", clickA < 0.12)
        assertTrue("incoming jump $clickB", clickB < 0.12)
        // Build-up (8 beats at the default 120 BPM) ends ~4 s in, after the swap at 3 s:
        // its drop must land on the incoming player.
        val sub = tone(outB, 45.0, SR * 4, SR * 5) / (tone(b, 45.0, SR * 4, SR * 5) + 1e-9)
        assertTrue("sub on incoming after build-up: $sub", sub > 20)
        val subA = tone(outA, 45.0, SR * 4, SR * 5)
        assertTrue("outgoing must not get the drop: $subA", subA < tone(outB, 45.0, SR * 4, SR * 5) * 0.2)
    }

    @Test
    fun `combo build up then bass drop then echo out plays in order`() {
        val engine = DjFxEngine()
        val processor = newProcessor(engine)
        val input = music(seconds = 22f)
        val output =
            runWithEvents(
                processor,
                input,
                listOf(
                    10.0 to {
                        engine.enqueue(DjFx.BUILD_UP)
                        engine.enqueue(DjFx.BASS_DROP, append = true)
                        engine.enqueue(DjFx.ECHO_OUT, append = true)
                    },
                ),
            )
        val bassCut = tone(output, 55.0, SR * 12, SR * 13) / tone(input, 55.0, SR * 12, SR * 13)
        assertTrue("build-up bass ratio $bassCut", bassCut < 0.7)
        val sub = tone(output, 45.0, SR * 14 + SR / 2, SR * 15 + SR / 2) / tone(input, 45.0, SR * 14 + SR / 2, SR * 15 + SR / 2)
        assertTrue("drop sub ratio $sub", sub > 1.5)
        // Echo out: the dry track is gone, only band-limited echoes remain.
        val from = SR * 17 + SR * 45 / 100
        val to = SR * 17 + SR * 85 / 100
        val echoLevel = rms(output, from, to)
        val bassLeft = tone(output, 55.0, from, to) / tone(input, 55.0, from, to)
        assertTrue("echo tail level $echoLevel", echoLevel > 0.005)
        assertTrue("dry bass still present $bassLeft", bassLeft < 0.5)
    }

    @Test
    fun `smart transitions switch really controls the transition audio`() {
        val input = music(seconds = 4f)

        val offEngine = DjFxEngine()
        val offIncoming = newProcessor(offEngine)
        offEngine.setSmartTransitions(false)
        val startedOff = offEngine.startSmartTransition(newProcessor(offEngine), offIncoming, 3_000)
        offEngine.onPrimaryChanged(offIncoming)
        val outOff = runWithEvents(offIncoming, input, emptyList())

        val onEngine = DjFxEngine()
        val onIncoming = newProcessor(onEngine)
        val startedOn = onEngine.startSmartTransition(newProcessor(onEngine), onIncoming, 3_000)
        onEngine.onPrimaryChanged(onIncoming)
        val outOn = runWithEvents(onIncoming, input, emptyList())

        assertTrue("switch off must not start a transition", !startedOff)
        assertTrue("switch off must leave the audio untouched", outOff.contentEquals(input))
        assertTrue("switch on must start a transition", startedOn)
        val bass = tone(outOn, 55.0, SR / 2, SR) / tone(input, 55.0, SR / 2, SR)
        assertTrue("switch on must hold the incoming bass back: $bass", bass < 0.5)
    }

    @Test
    fun `presets scale the effect strength`() {
        val ratios =
            DjFxPreset.entries.map { preset ->
                val engine = DjFxEngine()
                val processor = newProcessor(engine)
                engine.applyPreset(preset)
                engine.toggle(DjFx.BASS_BOOST)
                // A quieter mix, so the bass limiter (which caps loud bass to avoid
                // distortion) does not flatten the difference between presets.
                val input = music(seconds = 3f, scale = 0.4)
                val out = runWithEvents(processor, input, emptyList())
                val peak = (SR until SR * 3).maxOf { abs(out[it * CH].toInt()) } / 32768.0
                assertTrue("$preset peaked at $peak", peak < 0.9)
                tone(out, 55.0, SR, SR * 3) / tone(input, 55.0, SR, SR * 3)
            }
        println("DJFX bass boost at 55 Hz by preset: $ratios")
        assertTrue("$ratios", ratios[0] < ratios[1] && ratios[1] < ratios[2] && ratios[0] > 1.3)
    }

    // ---------------------------------------------------------------- helpers

    private fun newProcessor(engine: DjFxEngine): DjFxAudioProcessor {
        val processor = DjFxAudioProcessor(engine)
        engine.enabled = true
        engine.onPrimaryChanged(processor)
        processor.configure(AudioProcessor.AudioFormat(SR, CH, C.ENCODING_PCM_16BIT))
        @Suppress("DEPRECATION")
        processor.flush()
        return processor
    }

    private fun lockTempo(
        processor: DjFxAudioProcessor,
        buffers: List<ByteBuffer>,
        out: ShortArray,
    ) {
        feed(processor, buffers.take(buffers.size / 3), out, 0)
        processor.runTempoAnalysis()
    }

    private fun feed(
        processor: DjFxAudioProcessor,
        buffers: List<ByteBuffer>,
        out: ShortArray,
        offset: Int,
    ) {
        var written = offset
        for (buffer in buffers) {
            buffer.rewind()
            processor.queueInput(buffer)
            val result = processor.output
            while (result.hasRemaining()) {
                val v = result.getShort()
                if (written < out.size) out[written++] = v
            }
        }
    }

    private fun chunks(input: ShortArray): List<ByteBuffer> {
        val total = input.size / CH
        val list = ArrayList<ByteBuffer>()
        var frame = 0
        while (frame < total) {
            val count = minOf(CHUNK, total - frame)
            val buffer = ByteBuffer.allocateDirect(count * CH * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until count * CH) buffer.putShort(input[frame * CH + i])
            buffer.flip()
            list.add(buffer)
            frame += count
        }
        return list
    }

    private fun runWithEvents(
        processor: DjFxAudioProcessor,
        input: ShortArray,
        events: List<Pair<Double, () -> Unit>>,
    ): ShortArray {
        val out = ShortArray(input.size)
        val pending = events.sortedBy { it.first }.toMutableList()
        var frame = 0
        val total = input.size / CH
        while (frame < total) {
            val count = minOf(CHUNK, total - frame)
            while (pending.isNotEmpty() && pending.first().first * SR < frame + count) pending.removeAt(0).second()
            if (frame / SR != (frame + count) / SR) processor.runTempoAnalysis()
            process(processor, input, out, frame, count)
            frame += count
        }
        return out
    }

    private fun process(
        processor: DjFxAudioProcessor,
        input: ShortArray,
        out: ShortArray,
        frame: Int,
        count: Int,
    ) {
        val buffer = ByteBuffer.allocateDirect(count * CH * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until count * CH) buffer.putShort(input[frame * CH + i])
        buffer.flip()
        processor.queueInput(buffer)
        val result = processor.output
        var w = frame * CH
        while (result.hasRemaining()) out[w++] = result.getShort()
    }

    private fun copy(
        input: ShortArray,
        out: ShortArray,
        frame: Int,
        count: Int,
    ) {
        System.arraycopy(input, frame * CH, out, frame * CH, count * CH)
    }

    /** 120 BPM: kick, bass line, off-beat hat and a pad. */
    private fun music(
        seconds: Float,
        scale: Double = 1.0,
    ): ShortArray {
        val frames = (SR * seconds).toInt()
        val out = ShortArray(frames * CH)
        val beat = SR / 2
        for (n in 0 until frames) {
            val inBeat = n % beat
            val t = inBeat / SR.toDouble()
            val kick = if (inBeat < SR / 5) 0.2 * sin(2 * PI * 55.0 * t) * exp(-t * 12) else 0.0
            val bass = 0.08 * sin(2 * PI * 55.0 * n / SR) + 0.04 * sin(2 * PI * 82.5 * n / SR)
            val hatPhase = (n + beat / 2) % beat
            val hat = if (hatPhase < SR / 50) 0.05 * sin(2 * PI * 9000.0 * n / SR) * (1 - hatPhase / (SR / 50.0)) else 0.0
            val pad = 0.06 * sin(2 * PI * 6000.0 * n / SR)
            val s = ((kick + bass + hat + pad) * scale * 32767).toInt().toShort()
            out[n * 2] = s
            out[n * 2 + 1] = s
        }
        return out
    }

    private fun sine(
        seconds: Float,
        hz: Double,
        amp: Double,
    ): ShortArray {
        val frames = (SR * seconds).toInt()
        val out = ShortArray(frames * CH)
        for (n in 0 until frames) {
            val s = (amp * sin(2 * PI * hz * n / SR) * 32767).toInt().toShort()
            out[n * 2] = s
            out[n * 2 + 1] = s
        }
        return out
    }

    private fun maxStep(
        pcm: ShortArray,
        from: Int,
        to: Int,
    ): Double {
        var worst = 0.0
        for (f in max(from, 1) until to) {
            for (c in 0 until CH) {
                val d = abs(pcm[f * CH + c] - pcm[(f - 1) * CH + c]) / 32768.0
                if (d > worst) worst = d
            }
        }
        return worst
    }

    private fun rms(
        pcm: ShortArray,
        from: Int,
        to: Int,
    ): Double {
        var sum = 0.0
        for (f in from until to) {
            val v = pcm[f * CH] / 32768.0
            sum += v * v
        }
        return sqrt(sum / (to - from))
    }

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
            val s = pcm[f * CH] / 32768.0 + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        return sqrt(max(0.0, s1 * s1 + s2 * s2 - coeff * s1 * s2))
    }

    private companion object {
        const val SR = 44_100
        const val CH = 2
        const val CHUNK = 1024
    }
}
