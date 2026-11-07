/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

/**
 * Rules that turn raw playback into taste signals. Kept free of Android types so they can be
 * unit tested directly.
 */
object ListenSignals {
    /** Songs shorter than this are not judged by the half-length rule (intros, jingles). */
    private const val MIN_SHORT_SONG_THRESHOLD_MS = 5_000L

    /**
     * Play time after which a listen counts. Normally the user's history setting (30 s by default),
     * but a short song only needs half of its length, so a 40 s track counts after 20 s.
     */
    fun meaningfulThresholdMs(
        durationMs: Long?,
        historyThresholdMs: Long,
    ): Long {
        if (durationMs == null || durationMs <= 0) return historyThresholdMs
        val halfSong = maxOf(durationMs / 2, minOf(MIN_SHORT_SONG_THRESHOLD_MS, durationMs))
        return minOf(historyThresholdMs, halfSong)
    }

    fun isMeaningfulListen(
        playedMs: Long,
        durationMs: Long?,
        historyThresholdMs: Long,
    ): Boolean = playedMs >= meaningfulThresholdMs(durationMs, historyThresholdMs)

    /**
     * A skip is the user moving on to the next song before the listen became meaningful.
     * Going back to a previous song, automatic transitions and crossfades are not skips.
     */
    fun isSkip(
        leftAtPositionMs: Long,
        durationMs: Long?,
        historyThresholdMs: Long,
        movedToNextSong: Boolean,
    ): Boolean {
        if (!movedToNextSong || leftAtPositionMs < 0) return false
        return leftAtPositionMs < meaningfulThresholdMs(durationMs, historyThresholdMs)
    }
}
