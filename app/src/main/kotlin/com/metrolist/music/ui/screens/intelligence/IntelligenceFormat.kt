/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.intelligence

import kotlin.math.roundToInt

/** Whole hours and the remaining whole minutes of a listening time. */
data class ListeningDuration(
    val hours: Long,
    val minutes: Long,
)

/** Splits [ms] into hours and minutes, rounding down; negative values count as zero. */
fun listeningDuration(ms: Long): ListeningDuration {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    return ListeningDuration(hours = totalMinutes / 60L, minutes = totalMinutes % 60L)
}

/** A completion rate (0.0-1.0) as a whole percent, or null when the profile has no timed plays. */
fun completionPercent(rate: Double?): Int? {
    if (rate == null || rate.isNaN()) return null
    return (rate * 100).roundToInt().coerceIn(0, 100)
}

/** Only YouTube Music channel ids open an artist page; local and placeholder artists do not. */
fun canOpenArtistPage(artistId: String): Boolean = artistId.startsWith("UC")
