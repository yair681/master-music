/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * The default chain with the DJ effects appended at the very end.
 *
 * DJ effects deliberately create silence (drops, cuts). Placed before the silence
 * detection and skipping processors, that silence was skipped as if it were part of the
 * song, which made playback jump. At the end of the chain the detectors only ever see
 * the original music, and the effects run at the real playback speed.
 */
@UnstableApi
class DjFxProcessorChain(
    audioProcessors: Array<AudioProcessor>,
    silenceSkippingAudioProcessor: SilenceSkippingAudioProcessor,
    sonicAudioProcessor: SonicAudioProcessor,
    djFxProcessor: AudioProcessor,
) : DefaultAudioSink.DefaultAudioProcessorChain(audioProcessors, silenceSkippingAudioProcessor, sonicAudioProcessor) {
    private val processors: Array<AudioProcessor> = super.getAudioProcessors() + djFxProcessor

    override fun getAudioProcessors(): Array<AudioProcessor> = processors
}
