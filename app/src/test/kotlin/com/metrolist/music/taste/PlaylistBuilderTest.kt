/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PlaylistBuilderTest {
    private val now = LocalDateTime.of(2026, 11, 1, 12, 0)

    /** Payload is the song length in ms (null = unknown). */
    private fun rec(
        id: String,
        artist: String = "artist_$id",
        durationMs: Long? = 240_000L,
    ) = Recommendation(
        song = RecSong(id, "Song $id", listOf(RecArtist(artist, artist)), durationMs),
        score = 1.0,
        reason = RecommendationReason.BasedOnListening,
        isNew = true,
    )

    private fun fill(
        ranked: List<Recommendation<Long?>>,
        targetMs: Long? = null,
        targetCount: Int? = null,
        perArtist: Int? = PlaylistBuilder.MAX_PER_ARTIST,
    ) = PlaylistBuilder.fillToDuration(ranked, { it }, targetMs, targetCount, perArtist)

    private fun taste(
        id: String,
        score: Double = 2.0,
        listens: Int = 3,
        liked: Boolean = false,
        disliked: Boolean = false,
        lastPlayed: LocalDateTime? = null,
    ) = SongTaste(
        songId = id,
        score = score,
        listens = listens,
        recentListens = 0,
        listeningTimeMs = 0,
        completionRate = null,
        skips = 0,
        skipRate = 0.0,
        liked = liked,
        disliked = disliked,
        moreLikeThis = 0,
        lastPlayed = lastPlayed,
    )

    private fun artist(
        id: String,
        score: Double,
    ) = ArtistTaste(id, id, score, 1, 0, 1, 0, 0, 0, null)

    private fun profile(
        songs: List<SongTaste>,
        artists: List<ArtistTaste> = emptyList(),
    ) = TasteProfile(songs, artists, 0, 0, 0, null, emptyList(), emptyList())

    @Test
    fun `fills until the target duration is reached`() {
        val ranked = (1..10).map { rec("s$it") }
        val result = fill(ranked, targetMs = 20 * 60_000L)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), result.songs.map { it.song.id })
        assertEquals(20 * 60_000L, result.totalMs)
    }

    @Test
    fun `skips a song that overshoots by more than two minutes`() {
        val ranked = listOf(rec("a", durationMs = 300_000L), rec("b", durationMs = 450_000L), rec("c", durationMs = 240_000L))
        val result = fill(ranked, targetMs = 600_000L)
        assertEquals(listOf("a", "c"), result.songs.map { it.song.id })
        assertEquals(540_000L, result.totalMs)
    }

    @Test
    fun `allows a small overshoot within two minutes`() {
        val ranked = listOf(rec("a", durationMs = 300_000L), rec("b", durationMs = 400_000L))
        val result = fill(ranked, targetMs = 600_000L)
        assertEquals(listOf("a", "b"), result.songs.map { it.song.id })
        assertEquals(700_000L, result.totalMs)
    }

    @Test
    fun `stops within ninety seconds of the target`() {
        val ranked = listOf(rec("a", durationMs = 530_000L), rec("b", durationMs = 60_000L))
        val result = fill(ranked, targetMs = 600_000L)
        assertEquals(listOf("a"), result.songs.map { it.song.id })
    }

    @Test
    fun `unknown durations are skipped only with a duration target`() {
        val ranked = listOf(rec("a", durationMs = null), rec("b", durationMs = 300_000L), rec("c", durationMs = null), rec("d", durationMs = 300_000L))
        assertEquals(listOf("b", "d"), fill(ranked, targetMs = 600_000L).songs.map { it.song.id })
        assertEquals(listOf("a", "b"), fill(ranked, targetCount = 2).songs.map { it.song.id })
    }

    @Test
    fun `count target and default count`() {
        val ranked = (1..30).map { rec("s$it") }
        assertEquals(3, fill(ranked, targetCount = 3).songs.size)
        assertEquals(PlaylistBuilder.DEFAULT_SONG_COUNT, fill(ranked).songs.size)
    }

    @Test
    fun `at most three songs per main artist unless only-artist mode`() {
        val ranked = (1..6).map { rec("s$it", artist = "same") }
        assertEquals(listOf("s1", "s2", "s3"), fill(ranked).songs.map { it.song.id })
        assertEquals(6, fill(ranked, perArtist = null).songs.size)
    }

    @Test
    fun `no duplicates`() {
        val ranked = listOf(rec("a"), rec("a"), rec("b"))
        assertEquals(listOf("a", "b"), fill(ranked).songs.map { it.song.id })
    }

    @Test
    fun `shorter result when there are not enough songs`() {
        val ranked = listOf(rec("a"), rec("b"))
        val result = fill(ranked, targetMs = 60 * 60_000L)
        assertEquals(2, result.songs.size)
        assertEquals(480_000L, result.totalMs)
    }

    @Test
    fun `category matching is stem based and case insensitive`() {
        val hebrew = listOf("מסיבה", "רגוע", "אימון", "רוק")
        assertEquals("רגוע", PlaylistBuilder.matchCategory(listOf("רגועים"), hebrew))
        assertEquals("רוק", PlaylistBuilder.matchCategory(listOf("לרוק"), hebrew))
        assertEquals("Chill", PlaylistBuilder.matchCategory(listOf("chill"), listOf("Workout", "Chill", "Party")))
        assertEquals("Workout", PlaylistBuilder.matchCategory(listOf("workouts"), listOf("Workout", "Chill")))
        assertEquals("Hip-Hop", PlaylistBuilder.matchCategory(listOf("hip", "hop"), listOf("Pop", "Hip-Hop")))
        assertEquals("מזרחית", PlaylistBuilder.matchCategory(listOf("מזרחי"), listOf("מזרחית", "פופ")))
        assertNull(PlaylistBuilder.matchCategory(listOf("xyz"), listOf("Workout", "Chill")))
        assertNull(PlaylistBuilder.matchCategory(emptyList(), listOf("Workout")))
    }

    @Test
    fun `whole category check for possessive requests`() {
        assertTrue(PlaylistBuilder.isWholeCategory("רוק", "רוק"))
        assertTrue(PlaylistBuilder.isWholeCategory("chill", "Chill"))
        assertFalse(PlaylistBuilder.isWholeCategory("עומר אדם", "רוק"))
    }

    @Test
    fun `disliked songs and disliked artists never appear`() {
        val p =
            profile(
                songs = listOf(taste("bad", score = TasteEngine.DISLIKED_SONG_SCORE, disliked = true), taste("ok")),
                artists = listOf(artist("hated", -3.0), artist("fine", 1.0)),
            )
        val recs = listOf(rec("bad", artist = "fine"), rec("ok", artist = "fine"), rec("x", artist = "hated"), rec("y", artist = "other"))
        assertEquals(listOf("ok", "y"), PlaylistBuilder.withoutDisliked(recs, p).map { it.song.id })
    }

    @Test
    fun `flags filter by local history`() {
        val p =
            profile(
                listOf(
                    taste("heard", listens = 4, lastPlayed = now.minusDays(2)),
                    taste("liked", listens = 1, liked = true, lastPlayed = now.minusDays(90)),
                    taste("old", listens = 5, lastPlayed = now.minusDays(120)),
                    taste("oldDisliked", score = -5.0, disliked = true, lastPlayed = now.minusDays(120)),
                ),
            )
        val recs = listOf(rec("heard"), rec("liked"), rec("old"), rec("oldDisliked"), rec("fresh"))

        fun ids(request: PlaylistRequest) = PlaylistBuilder.applyFlags(recs, request, p, now).map { it.song.id }

        assertEquals(listOf("fresh"), ids(PlaylistRequest("", familiarity = Familiarity.NEW_ONLY)))
        assertEquals(listOf("heard", "liked", "old", "oldDisliked"), ids(PlaylistRequest("", familiarity = Familiarity.FAMILIAR_ONLY)))
        assertEquals(listOf("liked"), ids(PlaylistRequest("", likedOnly = true)))
        assertEquals(listOf("liked", "old"), ids(PlaylistRequest("", forgotten = true)))
        assertEquals(listOf("liked"), ids(PlaylistRequest("", likedOnly = true, forgotten = true)))
    }

    @Test
    fun `forgotten needs a positive score and sixty days`() {
        assertTrue(PlaylistBuilder.isForgotten(taste("a", lastPlayed = now.minusDays(61)), now))
        assertFalse(PlaylistBuilder.isForgotten(taste("a", lastPlayed = now.minusDays(59)), now))
        assertFalse(PlaylistBuilder.isForgotten(taste("a", score = -1.0, lastPlayed = now.minusDays(100)), now))
        assertFalse(PlaylistBuilder.isForgotten(taste("a", lastPlayed = null), now))
        assertEquals(61, PlaylistBuilder.daysSince(now.minusDays(61), now))
    }

    @Test
    fun `taste engine carries the last played time`() {
        val last = now.minusDays(5)
        val signals =
            SongSignals(
                songId = "a",
                durationSec = 200,
                liked = false,
                listens = 2,
                playMs = 400_000,
                recentListens = 2,
                midListens = 0,
                completionSum = 2.0,
                timedListens = 2,
                skips = 0,
                skipCompletionSum = 0.0,
                timedSkips = 0,
                skipPositionMs = 0,
                moreLikeThis = 0,
                lastPlayed = last,
            )
        val profile = TasteEngine.build(listOf(signals), emptyList(), emptySet())
        assertEquals(last, profile.song("a")!!.lastPlayed)
    }
}
