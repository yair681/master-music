/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.stats

import com.metrolist.music.db.MusicDatabase
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** The periods the insights screen can show. */
enum class InsightsPeriod {
    LAST_30_DAYS,
    LAST_12_MONTHS,
    ALL_TIME,
    ;

    fun start(now: LocalDateTime): LocalDateTime =
        when (this) {
            LAST_30_DAYS -> now.minusDays(30)
            LAST_12_MONTHS -> now.minusMonths(12)
            ALL_TIME -> LocalDateTime.of(1970, 1, 1, 0, 0)
        }
}

/** Everything the insights screen shows for one period, all of it read from the local database. */
data class ListeningInsights(
    val period: InsightsPeriod,
    val totalListens: Int,
    val totalPlayTimeMs: Long,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val newSongs: Int,
    val skips: Int,
    /** 24 hour buckets of the period. */
    val hours: List<TimeBucket>,
    /** 7 weekday buckets of the period, 0 = Sunday. */
    val weekdays: List<TimeBucket>,
    /** The last 12 months, whatever the period. */
    val months: List<MonthBar>,
    /** The last 30 days, whatever the period. */
    val days: List<DayBar>,
    /** Main artists of the period, most listened first. */
    val topArtists: List<ArtistPeriodTime>,
    val dna: TasteDna,
) {
    val isEmpty: Boolean get() = totalListens == 0
}

@Singleton
class InsightsRepository
    @Inject
    constructor(
        private val database: MusicDatabase,
    ) {
        suspend fun load(
            period: InsightsPeriod,
            now: LocalDateTime = LocalDateTime.now(),
        ): ListeningInsights {
            val from = period.start(now)
            val songs = database.songPeriodStats(from, now)
            val artists = database.mainArtistPeriodTime(from, now)
            val skips = database.skipSummary(from, now)
            val firstListens = database.firstListenCount(from, now)
            val hours = ListeningStats.fillHours(database.listeningByHour(from, now))
            val weekdays = ListeningStats.fillWeekdays(database.listeningByWeekday(from, now))

            val thisMonth = YearMonth.from(now)
            val monthsFrom = thisMonth.minusMonths(MONTHS - 1L).atDay(1).atStartOfDay().minusNanos(ONE_MS_NANOS)
            val months = ListeningStats.fillMonths(database.listeningByMonth(monthsFrom, now), thisMonth, MONTHS)

            val today = now.toLocalDate()
            val daysFrom = today.minusDays(DAYS - 1L).atStartOfDay().minusNanos(ONE_MS_NANOS)
            val days = ListeningStats.fillDays(database.listeningByDay(daysFrom, now), today, DAYS)

            return ListeningInsights(
                period = period,
                totalListens = songs.sumOf { it.listens },
                totalPlayTimeMs = songs.sumOf { it.playTimeMs },
                distinctSongs = database.distinctSongCount(from, now),
                distinctArtists = database.distinctArtistCount(from, now),
                newSongs = firstListens,
                skips = skips.skips,
                hours = hours,
                weekdays = weekdays,
                months = months,
                days = days,
                topArtists = artists,
                dna =
                    TasteDna.compute(
                        TasteDnaInput(
                            songs = songs,
                            mainArtists = artists,
                            firstListens = firstListens,
                            skips = skips,
                            hours = hours,
                            weekdays = weekdays,
                        ),
                    ),
            )
        }

        companion object {
            const val MONTHS = 12
            const val DAYS = 30

            // Ranges are (from, to]; starting 1 ms early keeps a listen at exactly midnight.
            private const val ONE_MS_NANOS = 1_000_000L
        }
    }
