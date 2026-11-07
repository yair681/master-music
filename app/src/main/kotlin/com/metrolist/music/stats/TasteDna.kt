/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.stats

/** Everything [TasteDna.compute] reads, all of it from the local database for one period. */
data class TasteDnaInput(
    val songs: List<SongPeriodStats>,
    /** Listening time per main artist in the period. */
    val mainArtists: List<ArtistPeriodTime>,
    /** Songs whose first-ever listen falls inside the period. */
    val firstListens: Int,
    val skips: SkipSummary,
    val hours: List<TimeBucket>,
    val weekdays: List<TimeBucket>,
)

/**
 * Measured listening habits for a period. Every value is a plain ratio of counts or listening time
 * from the device's history, or null when the period has nothing to measure it on. No trait is
 * guessed: there are no genres, moods or audio features here because the app does not store any.
 */
data class TasteDna(
    /** Share of listening time that went to the single most played main artist. */
    val topArtistShare: Double?,
    /** Share of listening time that went to the five most played main artists. */
    val topFiveArtistShare: Double?,
    /** Different main artists per listen (1.0 = a different artist every time). */
    val artistVariety: Double?,
    /** Share of listens that were the first-ever listen of a song. */
    val discoveryRate: Double?,
    /** Share of listens that went to songs played at least [REPEAT_MIN_LISTENS] times in the period. */
    val repeatRate: Double?,
    /** Average share of a song that was heard, over listens and skips with a known duration. */
    val completionRate: Double?,
    /** Skips divided by listens plus skips. */
    val skipRate: Double?,
    /** Share of the songs heard in the period that are liked. */
    val likedShare: Double?,
    /** Hour of day (0-23) with the most listening time. */
    val peakHour: Int?,
    /** Weekday (0 = Sunday) with the most listening time. */
    val peakWeekday: Int?,
) {
    val isEmpty: Boolean
        get() =
            listOf(
                topArtistShare, topFiveArtistShare, artistVariety, discoveryRate, repeatRate,
                completionRate, skipRate, likedShare,
            ).all { it == null } && peakHour == null && peakWeekday == null

    companion object {
        const val REPEAT_MIN_LISTENS = 3
        const val TOP_ARTISTS = 5

        val EMPTY = TasteDna(null, null, null, null, null, null, null, null, null, null)

        fun compute(input: TasteDnaInput): TasteDna {
            val totalListens = input.songs.sumOf { it.listens }
            val totalTimeMs = input.songs.sumOf { it.playTimeMs }
            val artistTimes = input.mainArtists.map { it.playTimeMs }.filter { it > 0 }.sortedDescending()

            return TasteDna(
                topArtistShare = ratio(artistTimes.firstOrNull() ?: 0L, totalTimeMs, artistTimes.isNotEmpty()),
                topFiveArtistShare = ratio(artistTimes.take(TOP_ARTISTS).sum(), totalTimeMs, artistTimes.isNotEmpty()),
                artistVariety = ratio(input.mainArtists.count { it.listens > 0 }.toLong(), totalListens.toLong()),
                discoveryRate = ratio(input.firstListens.toLong(), totalListens.toLong()),
                repeatRate =
                    ratio(
                        input.songs.filter { it.listens >= REPEAT_MIN_LISTENS }.sumOf { it.listens }.toLong(),
                        totalListens.toLong(),
                    ),
                completionRate =
                    ratio(
                        input.songs.sumOf { it.completionSum } + input.skips.completionSum,
                        (input.songs.sumOf { it.timedListens } + input.skips.timedSkips).toDouble(),
                    ),
                skipRate = ratio(input.skips.skips.toLong(), (totalListens + input.skips.skips).toLong()),
                likedShare = ratio(input.songs.count { it.liked }.toLong(), input.songs.size.toLong()),
                peakHour = ListeningStats.peak(input.hours),
                peakWeekday = ListeningStats.peak(input.weekdays),
            )
        }

        private fun ratio(
            part: Long,
            whole: Long,
            hasData: Boolean = true,
        ): Double? = if (!hasData || whole <= 0L) null else (part.toDouble() / whole).coerceIn(0.0, 1.0)

        private fun ratio(
            part: Double,
            whole: Double,
        ): Double? = if (whole <= 0.0 || part.isNaN()) null else (part / whole).coerceIn(0.0, 1.0)
    }
}
