/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteDnaTest {
    private fun song(
        id: String,
        listens: Int,
        playTimeMs: Long = listens * 100L,
        liked: Boolean = false,
        completionSum: Double = 0.0,
        timedListens: Int = 0,
    ) = SongPeriodStats(id, listens, playTimeMs, liked, completionSum, timedListens)

    private fun artist(
        id: String,
        listens: Int,
        playTimeMs: Long,
    ) = ArtistPeriodTime(id, id, null, listens, playTimeMs)

    private fun input(
        songs: List<SongPeriodStats> = emptyList(),
        artists: List<ArtistPeriodTime> = emptyList(),
        firstListens: Int = 0,
        skips: SkipSummary = SkipSummary.NONE,
        hours: List<TimeBucket> = ListeningStats.fillHours(emptyList()),
        weekdays: List<TimeBucket> = ListeningStats.fillWeekdays(emptyList()),
    ) = TasteDnaInput(songs, artists, firstListens, skips, hours, weekdays)

    @Test
    fun `no data gives no traits`() {
        val dna = TasteDna.compute(input())
        assertEquals(TasteDna.EMPTY, dna)
        assertTrue(dna.isEmpty)
    }

    @Test
    fun `top artist and top five shares divide by the whole listening time`() {
        val songs = listOf(song("s", listens = 10, playTimeMs = 1_000L))
        val artists =
            listOf(
                artist("a2", 1, 100L),
                artist("a1", 1, 400L),
                artist("a3", 1, 100L),
                artist("a4", 1, 100L),
                artist("a5", 1, 100L),
                artist("a6", 1, 100L),
            )
        val dna = TasteDna.compute(input(songs = songs, artists = artists))
        // Unordered input: the largest artist still counts as the top one.
        assertEquals(0.4, dna.topArtistShare!!, 1e-9)
        // 400 + four of the 100s; the sixth artist is left out.
        assertEquals(0.8, dna.topFiveArtistShare!!, 1e-9)
    }

    @Test
    fun `artist shares are null without artists or without listening time`() {
        val noArtists = TasteDna.compute(input(songs = listOf(song("s", 3))))
        assertNull(noArtists.topArtistShare)
        assertNull(noArtists.topFiveArtistShare)

        val noTime = TasteDna.compute(input(songs = listOf(song("s", 3, playTimeMs = 0L)), artists = listOf(artist("a", 3, 0L))))
        assertNull(noTime.topArtistShare)
    }

    @Test
    fun `artist variety is main artists per listen`() {
        val dna =
            TasteDna.compute(
                input(
                    songs = listOf(song("s1", 6), song("s2", 2)),
                    artists = listOf(artist("a", 6, 600L), artist("b", 2, 200L)),
                ),
            )
        assertEquals(2.0 / 8.0, dna.artistVariety!!, 1e-9)
    }

    @Test
    fun `discovery is first-ever listens over listens and is capped at one`() {
        val songs = listOf(song("s1", 3), song("s2", 1))
        assertEquals(0.5, TasteDna.compute(input(songs = songs, firstListens = 2)).discoveryRate!!, 1e-9)
        assertEquals(0.0, TasteDna.compute(input(songs = songs, firstListens = 0)).discoveryRate!!, 1e-9)
        assertEquals(1.0, TasteDna.compute(input(songs = songs, firstListens = 9)).discoveryRate!!, 1e-9)
        assertNull(TasteDna.compute(input(firstListens = 2)).discoveryRate)
    }

    @Test
    fun `repeat rate counts listens of songs played three or more times`() {
        val songs = listOf(song("three", 3), song("five", 5), song("two", 2))
        // (3 + 5) of 10 listens; the song played twice does not count.
        assertEquals(0.8, TasteDna.compute(input(songs = songs)).repeatRate!!, 1e-9)
        assertEquals(0.0, TasteDna.compute(input(songs = listOf(song("two", 2)))).repeatRate!!, 1e-9)
    }

    @Test
    fun `completion averages over timed listens and timed skips`() {
        val songs = listOf(song("s", 4, completionSum = 3.0, timedListens = 4), song("untimed", 2))
        val skips = SkipSummary(skips = 2, completionSum = 0.5, timedSkips = 1)
        assertEquals(3.5 / 5.0, TasteDna.compute(input(songs = songs, skips = skips)).completionRate!!, 1e-9)
    }

    @Test
    fun `completion is null when no play has a known duration`() {
        val dna = TasteDna.compute(input(songs = listOf(song("untimed", 2)), skips = SkipSummary(1, 0.0, 0)))
        assertNull(dna.completionRate)
    }

    @Test
    fun `skip rate is skips over listens plus skips`() {
        val songs = listOf(song("s", 6))
        assertEquals(0.25, TasteDna.compute(input(songs = songs, skips = SkipSummary(2, 0.0, 0))).skipRate!!, 1e-9)
        assertEquals(0.0, TasteDna.compute(input(songs = songs)).skipRate!!, 1e-9)
        // Only skips, no listens: every play was a skip.
        assertEquals(1.0, TasteDna.compute(input(skips = SkipSummary(3, 0.0, 0))).skipRate!!, 1e-9)
    }

    @Test
    fun `liked share counts songs, not listens`() {
        val songs = listOf(song("liked", 10, liked = true), song("a", 1), song("b", 1), song("c", 1))
        assertEquals(0.25, TasteDna.compute(input(songs = songs)).likedShare!!, 1e-9)
        assertNull(TasteDna.compute(input()).likedShare)
    }

    @Test
    fun `peak hour and weekday come from the buckets`() {
        val dna =
            TasteDna.compute(
                input(
                    songs = listOf(song("s", 3)),
                    hours = ListeningStats.fillHours(listOf(TimeBucket(22, 1, 500L), TimeBucket(7, 2, 100L))),
                    weekdays = ListeningStats.fillWeekdays(listOf(TimeBucket(6, 3, 600L))),
                ),
            )
        assertEquals(22, dna.peakHour)
        assertEquals(6, dna.peakWeekday)
        assertFalse(dna.isEmpty)
    }
}
