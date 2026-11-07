/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelligenceFormatTest {
    @Test
    fun durationSplitsIntoHoursAndMinutesRoundingDown() {
        assertEquals(ListeningDuration(0, 0), listeningDuration(0))
        assertEquals(ListeningDuration(0, 0), listeningDuration(59_999))
        assertEquals(ListeningDuration(0, 1), listeningDuration(60_000))
        assertEquals(ListeningDuration(0, 59), listeningDuration(59 * 60_000L + 59_999))
        assertEquals(ListeningDuration(1, 0), listeningDuration(60 * 60_000L))
        assertEquals(ListeningDuration(2, 5), listeningDuration((2 * 60 + 5) * 60_000L + 30_000))
        assertEquals(ListeningDuration(250, 0), listeningDuration(250L * 3_600_000L))
    }

    @Test
    fun negativeDurationCountsAsZero() {
        assertEquals(ListeningDuration(0, 0), listeningDuration(-5_000))
    }

    @Test
    fun completionPercentRoundsAndClamps() {
        assertNull(completionPercent(null))
        assertNull(completionPercent(Double.NaN))
        assertEquals(0, completionPercent(0.0))
        assertEquals(83, completionPercent(0.834))
        assertEquals(84, completionPercent(0.836))
        assertEquals(100, completionPercent(1.0))
        assertEquals(100, completionPercent(1.2))
        assertEquals(0, completionPercent(-0.1))
    }

    @Test
    fun onlyYouTubeChannelIdsOpenAnArtistPage() {
        assertTrue(canOpenArtistPage("UCabc123"))
        assertFalse(canOpenArtistPage("LAabc123"))
        assertFalse(canOpenArtistPage(""))
        assertFalse(canOpenArtistPage("uc_lowercase"))
    }
}
