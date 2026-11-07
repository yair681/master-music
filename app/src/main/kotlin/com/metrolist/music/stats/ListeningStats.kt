/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Listening grouped by hour of day (0-23) or weekday (0 = Sunday ... 6 = Saturday).
 * Read straight from the `event` table, one row per bucket that has listens.
 */
data class TimeBucket(
    val bucket: Int,
    val listens: Int,
    val playTimeMs: Long,
)

/** Listening grouped by a calendar label: `YYYY-MM` for months, `YYYY-MM-DD` for days. */
data class DateBucket(
    val label: String,
    val listens: Int,
    val playTimeMs: Long,
)

/** One song's listening inside a period. */
data class SongPeriodStats(
    val songId: String,
    val listens: Int,
    val playTimeMs: Long,
    val liked: Boolean,
    /** Sum over timed listens of min(1, playTime / duration). */
    val completionSum: Double,
    /** Listens of songs with a known duration. */
    val timedListens: Int,
)

/** Listening time of one main (first-credited) artist inside a period. */
data class ArtistPeriodTime(
    val artistId: String,
    val name: String,
    val thumbnailUrl: String?,
    val listens: Int,
    val playTimeMs: Long,
)

/** Skips recorded in `song_feedback` inside a period. */
data class SkipSummary(
    val skips: Int,
    /** Sum over timed skips of min(1, position / duration). */
    val completionSum: Double,
    val timedSkips: Int,
) {
    companion object {
        val NONE = SkipSummary(0, 0.0, 0)
    }
}

/** A month bar of the monthly chart. */
data class MonthBar(
    val month: YearMonth,
    val listens: Int,
    val playTimeMs: Long,
)

/** A day bar of the daily chart. */
data class DayBar(
    val date: LocalDate,
    val listens: Int,
    val playTimeMs: Long,
)

/** Pure helpers that turn the sparse SQL buckets into complete chart series. */
object ListeningStats {
    const val HOURS = 24
    const val WEEKDAYS = 7

    /** All 24 hours in order, with zeros for hours that have no listens. */
    fun fillHours(buckets: List<TimeBucket>): List<TimeBucket> = fill(buckets, HOURS)

    /** All 7 weekdays in SQLite order (0 = Sunday), with zeros for days that have no listens. */
    fun fillWeekdays(buckets: List<TimeBucket>): List<TimeBucket> = fill(buckets, WEEKDAYS)

    private fun fill(
        buckets: List<TimeBucket>,
        size: Int,
    ): List<TimeBucket> {
        val byBucket = buckets.filter { it.bucket in 0 until size }.groupBy { it.bucket }
        return (0 until size).map { index ->
            val rows = byBucket[index].orEmpty()
            TimeBucket(index, rows.sumOf { it.listens }, rows.sumOf { it.playTimeMs })
        }
    }

    /** The [count] months ending with [endMonth], oldest first, with zeros where nothing was played. */
    fun fillMonths(
        buckets: List<DateBucket>,
        endMonth: YearMonth,
        count: Int = 12,
    ): List<MonthBar> {
        val byLabel = buckets.associateBy { it.label }
        return (count - 1 downTo 0).map { back ->
            val month = endMonth.minusMonths(back.toLong())
            val row = byLabel[month.toString()]
            MonthBar(month, row?.listens ?: 0, row?.playTimeMs ?: 0L)
        }
    }

    /** The [count] days ending with [endDate], oldest first, with zeros where nothing was played. */
    fun fillDays(
        buckets: List<DateBucket>,
        endDate: LocalDate,
        count: Int = 30,
    ): List<DayBar> {
        val byLabel = buckets.associateBy { it.label }
        return (count - 1 downTo 0).map { back ->
            val date = endDate.minusDays(back.toLong())
            val row = byLabel[date.toString()]
            DayBar(date, row?.listens ?: 0, row?.playTimeMs ?: 0L)
        }
    }

    /**
     * The bucket with the most listening time (listen count breaks a tie, then the earlier bucket),
     * or null when nothing was played.
     */
    fun peak(buckets: List<TimeBucket>): Int? =
        buckets
            .filter { it.listens > 0 || it.playTimeMs > 0 }
            .sortedWith(compareByDescending<TimeBucket> { it.playTimeMs }.thenByDescending { it.listens }.thenBy { it.bucket })
            .firstOrNull()
            ?.bucket

    /** SQLite `%w` weekday (0 = Sunday) as a [DayOfWeek]. */
    fun dayOfWeek(sqliteWeekday: Int): DayOfWeek = if (sqliteWeekday == 0) DayOfWeek.SUNDAY else DayOfWeek.of(sqliteWeekday)

    /** SQLite weekday indexes ordered for a week that starts on [firstDay]. */
    fun weekdayOrder(firstDay: DayOfWeek): List<Int> {
        val start = firstDay.value % 7
        return (0 until WEEKDAYS).map { (start + it) % 7 }
    }
}
