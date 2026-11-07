/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import java.time.LocalDateTime

/**
 * Everything the device knows about one song, aggregated in SQL from `event`, `song` and
 * `song_feedback`. Only real playback data and explicit user feedback; no genre or mood guesses.
 */
data class SongSignals(
    val songId: String,
    /** Seconds, -1 when unknown. */
    val durationSec: Int,
    val liked: Boolean,
    /** Meaningful listens (rows in `event`). */
    val listens: Int,
    val playMs: Long,
    /** Meaningful listens in the last [TasteEngine.RECENT_DAYS] days. */
    val recentListens: Int,
    /** Meaningful listens between [TasteEngine.RECENT_DAYS] and [TasteEngine.MID_DAYS] days ago. */
    val midListens: Int,
    /** Sum of min(1, playTime / duration) over listens whose song length is known. */
    val completionSum: Double,
    val timedListens: Int,
    val skips: Int,
    /** Sum of min(1, position / duration) over skips whose song length is known. */
    val skipCompletionSum: Double,
    val timedSkips: Int,
    /** How long the user stayed in the song before skipping, summed. */
    val skipPositionMs: Long,
    val moreLikeThis: Int,
    /** Time of the latest meaningful listen, null when the song was never heard. */
    val lastPlayed: LocalDateTime? = null,
)

data class SongArtistLink(
    val songId: String,
    val artistId: String,
    val artistName: String,
    /** 0 = main artist, higher = featured. */
    val position: Int,
)
