/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class ListeningStatsTest {
    @Test
    fun `fillHours returns 24 ordered buckets with zeros`() {
        val filled = ListeningStats.fillHours(listOf(TimeBucket(21, 2, 300L), TimeBucket(0, 1, 50L)))
        assertEquals(24, filled.size)
        assertEquals((0..23).toList(), filled.map { it.bucket })
        assertEquals(TimeBucket(0, 1, 50L), filled[0])
        assertEquals(TimeBucket(21, 2, 300L), filled[21])
        assertEquals(TimeBucket(5, 0, 0L), filled[5])
        assertEquals(350L, filled.sumOf { it.playTimeMs })
    }

    @Test
    fun `fillHours of nothing is all zeros and ignores out of range buckets`() {
        val filled = ListeningStats.fillHours(listOf(TimeBucket(24, 9, 9L), TimeBucket(-1, 9, 9L)))
        assertEquals(24, filled.size)
        assertEquals(0, filled.sumOf { it.listens })
        assertEquals(24, ListeningStats.fillHours(emptyList()).size)
    }

    @Test
    fun `fillWeekdays returns 7 buckets from Sunday`() {
        val filled = ListeningStats.fillWeekdays(listOf(TimeBucket(5, 2, 300L)))
        assertEquals(7, filled.size)
        assertEquals((0..6).toList(), filled.map { it.bucket })
        assertEquals(TimeBucket(5, 2, 300L), filled[5])
        assertEquals(TimeBucket(0, 0, 0L), filled[0])
    }

    @Test
    fun `fillMonths covers the last 12 months oldest first across a year boundary`() {
        val filled =
            ListeningStats.fillMonths(
                listOf(DateBucket("2025-12", 2, 40L), DateBucket("2026-11", 4, 450L), DateBucket("2024-01", 1, 1L)),
                YearMonth.of(2026, 11),
            )
        assertEquals(12, filled.size)
        assertEquals(YearMonth.of(2025, 12), filled.first().month)
        assertEquals(MonthBar(YearMonth.of(2025, 12), 2, 40L), filled.first())
        assertEquals(MonthBar(YearMonth.of(2026, 11), 4, 450L), filled.last())
        assertEquals(MonthBar(YearMonth.of(2026, 6), 0, 0L), filled[6])
        // A month outside the window is dropped.
        assertEquals(490L, filled.sumOf { it.playTimeMs })
    }

    @Test
    fun `fillDays covers the last 30 days oldest first`() {
        val filled =
            ListeningStats.fillDays(
                listOf(DateBucket("2026-11-06", 2, 300L), DateBucket("2026-10-09", 1, 5L)),
                LocalDate.of(2026, 11, 7),
            )
        assertEquals(30, filled.size)
        assertEquals(DayBar(LocalDate.of(2026, 10, 9), 1, 5L), filled.first())
        assertEquals(DayBar(LocalDate.of(2026, 11, 7), 0, 0L), filled.last())
        assertEquals(DayBar(LocalDate.of(2026, 11, 6), 2, 300L), filled[28])
    }

    @Test
    fun `peak is the bucket with the most listening time`() {
        val hours = ListeningStats.fillHours(listOf(TimeBucket(8, 5, 100L), TimeBucket(21, 2, 300L)))
        assertEquals(21, ListeningStats.peak(hours))
    }

    @Test
    fun `peak ties break on listens and then on the earlier bucket`() {
        assertEquals(9, ListeningStats.peak(listOf(TimeBucket(8, 1, 100L), TimeBucket(9, 2, 100L))))
        assertEquals(8, ListeningStats.peak(listOf(TimeBucket(9, 1, 100L), TimeBucket(8, 1, 100L))))
    }

    @Test
    fun `peak of no listening is null`() {
        assertNull(ListeningStats.peak(ListeningStats.fillHours(emptyList())))
        assertNull(ListeningStats.peak(emptyList()))
    }

    @Test
    fun `sqlite weekdays map to DayOfWeek`() {
        assertEquals(DayOfWeek.SUNDAY, ListeningStats.dayOfWeek(0))
        assertEquals(DayOfWeek.MONDAY, ListeningStats.dayOfWeek(1))
        assertEquals(DayOfWeek.SATURDAY, ListeningStats.dayOfWeek(6))
    }

    @Test
    fun `weekday order follows the first day of the week`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), ListeningStats.weekdayOrder(DayOfWeek.SUNDAY))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 0), ListeningStats.weekdayOrder(DayOfWeek.MONDAY))
        assertEquals(listOf(6, 0, 1, 2, 3, 4, 5), ListeningStats.weekdayOrder(DayOfWeek.SATURDAY))
    }
}
