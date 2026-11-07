/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test
    fun boundariesFollowTheClock() {
        assertEquals(DayPart.NIGHT, DayPart.forHour(4))
        assertEquals(DayPart.MORNING, DayPart.forHour(5))
        assertEquals(DayPart.MORNING, DayPart.forHour(11))
        assertEquals(DayPart.AFTERNOON, DayPart.forHour(12))
        assertEquals(DayPart.AFTERNOON, DayPart.forHour(16))
        assertEquals(DayPart.EVENING, DayPart.forHour(17))
        assertEquals(DayPart.EVENING, DayPart.forHour(20))
        assertEquals(DayPart.NIGHT, DayPart.forHour(21))
        assertEquals(DayPart.NIGHT, DayPart.forHour(0))
        assertEquals(DayPart.NIGHT, DayPart.forHour(23))
    }

    @Test
    fun everyHourHasExactlyOneGreetingAndEachPartIsUsed() {
        val parts = (0..23).map { DayPart.forHour(it) }
        assertEquals(24, parts.size)
        assertEquals(DayPart.entries.toSet(), parts.toSet())
        assertEquals(7, parts.count { it == DayPart.MORNING })
        assertEquals(5, parts.count { it == DayPart.AFTERNOON })
        assertEquals(4, parts.count { it == DayPart.EVENING })
        assertEquals(8, parts.count { it == DayPart.NIGHT })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnHourOutsideTheDay() {
        DayPart.forHour(24)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsANegativeHour() {
        DayPart.forHour(-1)
    }
}
