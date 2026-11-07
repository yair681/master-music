/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteEngineTest {
    private fun song(
        id: String,
        listens: Int = 0,
        recent: Int = listens,
        mid: Int = 0,
        durationSec: Int = 200,
        playMsPerListen: Long = durationSec * 1000L,
        liked: Boolean = false,
        skips: Int = 0,
        skipAtMs: Long = 10_000L,
        moreLikeThis: Int = 0,
    ): SongSignals {
        val timed = durationSec > 0
        val perListen = if (timed) minOf(1.0, playMsPerListen / (durationSec * 1000.0)) else 0.0
        val perSkip = if (timed) minOf(1.0, skipAtMs / (durationSec * 1000.0)) else 0.0
        return SongSignals(
            songId = id,
            durationSec = durationSec,
            liked = liked,
            listens = listens,
            playMs = playMsPerListen * listens,
            recentListens = recent,
            midListens = mid,
            completionSum = perListen * listens,
            timedListens = if (timed) listens else 0,
            skips = skips,
            skipCompletionSum = perSkip * skips,
            timedSkips = if (timed) skips else 0,
            skipPositionMs = skipAtMs * skips,
            moreLikeThis = moreLikeThis,
        )
    }

    private fun score(
        s: SongSignals,
        disliked: Boolean = false,
    ) = TasteEngine.songScore(s, disliked)

    @Test
    fun `empty input gives the empty profile`() {
        assertSame(TasteProfile.EMPTY, TasteEngine.build(emptyList(), emptyList(), emptySet()))
        assertTrue(TasteProfile.EMPTY.isEmpty)
    }

    @Test
    fun `more listens score higher with diminishing returns`() {
        val one = score(song("a", listens = 1))
        val five = score(song("a", listens = 5))
        val ten = score(song("a", listens = 10))
        assertTrue(five > one)
        assertTrue(ten > five)
        assertTrue(ten - five < five - one)
    }

    @Test
    fun `recent listens weigh more than old ones`() {
        val recent = score(song("a", listens = 4, recent = 4))
        val middle = score(song("a", listens = 4, recent = 0, mid = 4))
        val old = score(song("a", listens = 4, recent = 0, mid = 0))
        assertTrue(recent > middle)
        assertTrue(middle > old)
    }

    @Test
    fun `finishing songs scores higher than leaving them halfway`() {
        val full = score(song("a", listens = 3, playMsPerListen = 200_000L))
        val half = score(song("a", listens = 3, playMsPerListen = 100_000L))
        assertTrue(full > half)
    }

    @Test
    fun `completion rate counts listens and skips`() {
        val s = song("a", listens = 1, playMsPerListen = 200_000L, skips = 1, skipAtMs = 20_000L)
        assertEquals((1.0 + 0.1) / 2, TasteEngine.completionRate(s)!!, 1e-9)
    }

    @Test
    fun `completion rate is unknown without a song length`() {
        assertNull(TasteEngine.completionRate(song("a", listens = 3, durationSec = -1)))
    }

    @Test
    fun `like adds a fixed bonus`() {
        assertEquals(2.0, score(song("a", listens = 2, liked = true)) - score(song("a", listens = 2)), 1e-9)
    }

    @Test
    fun `liked song without listens still counts`() {
        assertEquals(2.0, score(song("a", liked = true)), 1e-9)
    }

    @Test
    fun `more like this bonus is capped`() {
        val base = score(song("a", listens = 1))
        assertEquals(0.75, score(song("a", listens = 1, moreLikeThis = 1)) - base, 1e-9)
        assertEquals(2.25, score(song("a", listens = 1, moreLikeThis = 9)) - base, 1e-9)
    }

    @Test
    fun `one accidental skip costs little, repeated skips cost a lot`() {
        val clean = score(song("a", listens = 3))
        val oneSkip = score(song("a", listens = 3, skips = 1))
        val manySkips = score(song("a", listens = 3, skips = 6))
        assertTrue(oneSkip > clean * 0.75)
        assertTrue(manySkips < clean * 0.25)
    }

    @Test
    fun `song that is only ever skipped goes negative`() {
        assertTrue(score(song("a", skips = 4)) < 0)
    }

    @Test
    fun `disliked song gets the fixed low score`() {
        assertEquals(TasteEngine.DISLIKED_SONG_SCORE, score(song("a", listens = 20, liked = true), disliked = true), 0.0)
    }

    @Test
    fun `songs are ranked and favorites exclude weak and disliked songs`() {
        val profile =
            TasteEngine.build(
                listOf(
                    song("loved", listens = 10, liked = true),
                    song("once", listens = 1),
                    song("hated", listens = 10),
                ),
                emptyList(),
                setOf("hated"),
            )
        assertEquals(listOf("loved", "once", "hated"), profile.songs.map { it.songId })
        assertEquals(listOf("loved"), profile.favoriteSongs.map { it.songId })
        assertTrue(profile.song("hated")!!.disliked)
    }

    @Test
    fun `artist score weighs main artist fully and featured artist half`() {
        val profile =
            TasteEngine.build(
                listOf(song("s", listens = 5)),
                listOf(
                    SongArtistLink("s", "main", "Main", 0),
                    SongArtistLink("s", "feat", "Feat", 1),
                ),
                emptySet(),
            )
        val songScore = profile.song("s")!!.score
        assertEquals(songScore, profile.artist("main")!!.score, 1e-9)
        assertEquals(songScore / 2, profile.artist("feat")!!.score, 1e-9)
    }

    @Test
    fun `dislikes and skips pull an artist down`() {
        val links =
            listOf(
                SongArtistLink("good1", "good", "Good", 0),
                SongArtistLink("good2", "good", "Good", 0),
                SongArtistLink("skipped1", "skipped", "Skipped", 0),
                SongArtistLink("skipped2", "skipped", "Skipped", 0),
                SongArtistLink("mixed", "mixed", "Mixed", 0),
                SongArtistLink("mixedBad", "mixed", "Mixed", 0),
            )
        val profile =
            TasteEngine.build(
                listOf(
                    song("good1", listens = 3),
                    song("good2", listens = 3),
                    song("skipped1", listens = 1, skips = 5),
                    song("skipped2", listens = 1, skips = 5),
                    song("mixed", listens = 3),
                    song("mixedBad", listens = 3),
                ),
                links,
                setOf("mixedBad"),
            )
        assertEquals(listOf("good", "mixed", "skipped"), profile.artists.map { it.artistId })
        assertEquals(1, profile.artist("mixed")!!.dislikedSongs)
        assertEquals(10, profile.artist("skipped")!!.skips)
        assertTrue(profile.artist("skipped")!!.score < 0)
    }

    @Test
    fun `favorite artists need a positive score and enough listens`() {
        val profile =
            TasteEngine.build(
                listOf(song("a1", listens = 5), song("b1", listens = 1, liked = true)),
                listOf(SongArtistLink("a1", "a", "A", 0), SongArtistLink("b1", "b", "B", 0)),
                emptySet(),
            )
        assertEquals(listOf("a"), profile.favoriteArtists.map { it.artistId })
    }

    @Test
    fun `totals add real listening time and time before skips`() {
        val profile =
            TasteEngine.build(
                listOf(
                    song("a", listens = 2, playMsPerListen = 180_000L),
                    song("b", skips = 3, skipAtMs = 10_000L),
                ),
                emptyList(),
                emptySet(),
            )
        assertEquals(2 * 180_000L + 3 * 10_000L, profile.totalListeningTimeMs)
        assertEquals(2, profile.totalListens)
        assertEquals(3, profile.totalSkips)
        val expectedCompletion = (2 * 0.9 + 3 * 0.05) / 5
        assertEquals(expectedCompletion, profile.completionRate!!, 1e-9)
    }

    @Test
    fun `links to songs without signals are ignored`() {
        val profile =
            TasteEngine.build(
                listOf(song("a", listens = 1)),
                listOf(SongArtistLink("a", "x", "X", 0), SongArtistLink("ghost", "y", "Y", 0)),
                emptySet(),
            )
        assertEquals(listOf("x"), profile.artists.map { it.artistId })
    }
}
