/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenSignalsTest {
    private val history = 30_000L

    @Test
    fun `normal song uses the history setting`() {
        assertEquals(30_000L, ListenSignals.meaningfulThresholdMs(durationMs = 200_000L, historyThresholdMs = history))
    }

    @Test
    fun `unknown duration uses the history setting`() {
        assertEquals(30_000L, ListenSignals.meaningfulThresholdMs(durationMs = null, historyThresholdMs = history))
        assertEquals(30_000L, ListenSignals.meaningfulThresholdMs(durationMs = -1_000L, historyThresholdMs = history))
    }

    @Test
    fun `short song needs half of its length`() {
        assertEquals(20_000L, ListenSignals.meaningfulThresholdMs(durationMs = 40_000L, historyThresholdMs = history))
    }

    @Test
    fun `very short song never needs more than its own length`() {
        assertEquals(5_000L, ListenSignals.meaningfulThresholdMs(durationMs = 8_000L, historyThresholdMs = history))
        assertEquals(4_000L, ListenSignals.meaningfulThresholdMs(durationMs = 4_000L, historyThresholdMs = history))
    }

    @Test
    fun `lower history setting wins over half length`() {
        assertEquals(10_000L, ListenSignals.meaningfulThresholdMs(durationMs = 40_000L, historyThresholdMs = 10_000L))
    }

    @Test
    fun `meaningful listen boundary is inclusive`() {
        assertTrue(ListenSignals.isMeaningfulListen(30_000L, 200_000L, history))
        assertFalse(ListenSignals.isMeaningfulListen(29_999L, 200_000L, history))
        assertTrue(ListenSignals.isMeaningfulListen(20_000L, 40_000L, history))
    }

    @Test
    fun `leaving early for the next song is a skip`() {
        assertTrue(ListenSignals.isSkip(12_000L, 200_000L, history, movedToNextSong = true))
    }

    @Test
    fun `leaving after a meaningful listen is not a skip`() {
        assertFalse(ListenSignals.isSkip(45_000L, 200_000L, history, movedToNextSong = true))
    }

    @Test
    fun `going back or jumping elsewhere is not a skip`() {
        assertFalse(ListenSignals.isSkip(5_000L, 200_000L, history, movedToNextSong = false))
    }

    @Test
    fun `short song skip uses the short threshold`() {
        assertTrue(ListenSignals.isSkip(15_000L, 40_000L, history, movedToNextSong = true))
        assertFalse(ListenSignals.isSkip(25_000L, 40_000L, history, movedToNextSong = true))
    }

    @Test
    fun `negative position is ignored`() {
        assertFalse(ListenSignals.isSkip(-1L, 200_000L, history, movedToNextSong = true))
    }
}
