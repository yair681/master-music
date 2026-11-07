/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import androidx.annotation.StringRes
import com.metrolist.music.R

/** Part of the day on the device clock, used for the greeting at the top of Home. */
enum class DayPart(
    @StringRes val greetingRes: Int,
) {
    MORNING(R.string.greeting_morning),
    AFTERNOON(R.string.greeting_afternoon),
    EVENING(R.string.greeting_evening),
    NIGHT(R.string.greeting_night),
    ;

    companion object {
        /** Morning 05:00-11:59, afternoon 12:00-16:59, evening 17:00-20:59, night 21:00-04:59. */
        fun forHour(hour: Int): DayPart {
            require(hour in 0..23) { "hour must be 0-23, was $hour" }
            return when (hour) {
                in 5..11 -> MORNING
                in 12..16 -> AFTERNOON
                in 17..20 -> EVENING
                else -> NIGHT
            }
        }
    }
}
