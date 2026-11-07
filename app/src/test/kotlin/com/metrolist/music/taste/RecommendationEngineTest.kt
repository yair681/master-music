/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val day = 20_400L

    private fun signals(
        id: String,
        listens: Int = 0,
        recent: Int = listens,
        liked: Boolean = false,
        skips: Int = 0,
    ) = SongSignals(
        songId = id,
        durationSec = 200,
        liked = liked,
        listens = listens,
        playMs = 200_000L * listens,
        recentListens = recent,
        midListens = 0,
        completionSum = listens.toDouble(),
        timedListens = listens,
        skips = skips,
        skipCompletionSum = 0.05 * skips,
        timedSkips = skips,
        skipPositionMs = 10_000L * skips,
        moreLikeThis = 0,
    )

    private fun rec(
        id: String,
        artist: String = "artist-$id",
    ) = RecSong(id, "Title $id", listOf(RecArtist(artist, artist.uppercase())), payload = id)

    private fun link(
        songId: String,
        artist: String,
    ) = SongArtistLink(songId, artist, artist.uppercase(), 0)

    // ---- dayRandom ----

    @Test
    fun `day random is stable within a day and changes across days`() {
        val a = RecommendationEngine.dayRandom(day, "song")
        assertEquals(a, RecommendationEngine.dayRandom(day, "song"), 0.0)
        assertTrue(a >= 0.0 && a < 1.0)
        assertNotEquals(a, RecommendationEngine.dayRandom(day + 1, "song"), 0.0)
    }

    // ---- quick picks ----

    @Test
    fun `quick picks skip disliked, often skipped, recently played and unplayable songs`() {
        val profile =
            TasteEngine.build(
                listOf(
                    signals("good", listens = 6),
                    signals("disliked", listens = 9),
                    signals("skippy", listens = 1, skips = 4),
                    signals("justPlayed", listens = 8),
                    signals("notInLibrary", listens = 7),
                ),
                emptyList(),
                setOf("disliked"),
            )
        val songs = listOf("good", "disliked", "skippy", "justPlayed").associateWith { rec(it) }
        val picks = RecommendationEngine.quickPicks(profile, songs, recentlyPlayed = setOf("justPlayed"), dayKey = day)
        assertEquals(listOf("good"), picks.map { it.song.id })
        assertFalse(picks.single().isNew)
    }

    @Test
    fun `quick picks explain each pick from the data`() {
        val profile =
            TasteEngine.build(
                listOf(
                    signals("liked", listens = 1, liked = true),
                    signals("repeat", listens = 5, recent = 5),
                    signals("fav", listens = 4, recent = 0),
                    signals("fav2", listens = 4, recent = 0),
                    signals("other", listens = 1, recent = 0),
                ),
                listOf(link("fav", "star"), link("fav2", "star"), link("other", "someone")),
                emptySet(),
            )
        val songs =
            mapOf(
                "liked" to rec("liked"),
                "repeat" to rec("repeat"),
                "fav" to rec("fav", "star"),
                "fav2" to rec("fav2", "star"),
                "other" to rec("other", "someone"),
            )
        val reasons = RecommendationEngine.quickPicks(profile, songs, emptySet(), day).associate { it.song.id to it.reason }
        assertEquals(RecommendationReason.Liked, reasons["liked"])
        assertEquals(RecommendationReason.OnRepeat(5), reasons["repeat"])
        assertEquals(RecommendationReason.FavoriteArtist("STAR"), reasons["fav"])
        assertEquals(RecommendationReason.BasedOnListening, reasons["other"])
    }

    @Test
    fun `quick picks limit songs per artist and never repeat a song`() {
        val ids = (1..6).map { "s$it" }
        val profile = TasteEngine.build(ids.map { signals(it, listens = 3) }, ids.map { link(it, "same") }, emptySet())
        val songs = ids.associateWith { rec(it, "same") }
        val picks = RecommendationEngine.quickPicks(profile, songs, emptySet(), day, perArtist = 3)
        assertEquals(3, picks.size)
        assertEquals(picks.size, picks.map { it.song.id }.toSet().size)
    }

    // ---- seeds ----

    @Test
    fun `seeds come from favorites and are stable for the day`() {
        val profile = TasteEngine.build((1..20).map { signals("f$it", listens = 5 + it) }, emptyList(), emptySet())
        val seeds = RecommendationEngine.seeds(profile, day, count = 5)
        assertEquals(5, seeds.size)
        assertEquals(seeds, RecommendationEngine.seeds(profile, day, count = 5))
        val favorites = profile.favoriteSongs.take(15).map { it.songId }.toSet()
        assertTrue(seeds.all { it.songId in favorites })
    }

    // ---- discover ----

    @Test
    fun `discover only returns songs the user never heard`() {
        val profile = TasteEngine.build(listOf(signals("seed", listens = 5), signals("known", listens = 2)), emptyList(), emptySet())
        val batch = RelatedBatch("seed", "Seed", listOf(rec("seed"), rec("known"), rec("fresh"), rec("excluded")))
        val picks = RecommendationEngine.discover(profile, listOf(batch), exclude = setOf("excluded"))
        assertEquals(listOf("fresh"), picks.map { it.song.id })
        assertTrue(picks.single().isNew)
        assertEquals("seed", picks.single().seedId)
    }

    @Test
    fun `discover merges duplicates and favors songs several seeds agree on`() {
        val profile = TasteEngine.build(listOf(signals("a", listens = 5), signals("b", listens = 5)), emptyList(), emptySet())
        val picks =
            RecommendationEngine.discover(
                profile,
                listOf(
                    RelatedBatch("a", "A", listOf(rec("x"), rec("both"), rec("z1"))),
                    RelatedBatch("b", "B", listOf(rec("y"), rec("both"), rec("z2"))),
                ),
            )
        assertEquals("both", picks.first().song.id)
        assertEquals(5, picks.size)
    }

    @Test
    fun `discover boosts favorite artists, blocks disliked artists and explains why`() {
        val profile =
            TasteEngine.build(
                listOf(
                    signals("seed", listens = 4, liked = true),
                    signals("lovedSong", listens = 10, liked = true),
                    signals("bad1", listens = 1, skips = 6),
                    signals("bad2", listens = 1, skips = 6),
                ),
                listOf(link("lovedSong", "loved"), link("bad1", "hated"), link("bad2", "hated")),
                setOf("bad1", "bad2"),
            )
        val batch =
            RelatedBatch(
                "seed",
                "Seed Song",
                listOf(rec("plain1"), rec("plain2"), rec("fromHated", "hated"), rec("fromLoved", "loved")),
            )
        val picks = RecommendationEngine.discover(profile, listOf(batch))
        val ids = picks.map { it.song.id }
        assertFalse("fromHated" in ids)
        assertEquals("fromLoved", ids.first())
        assertEquals(RecommendationReason.FavoriteArtist("LOVED"), picks.first().reason)
        assertEquals(RecommendationReason.SimilarTo("Seed Song", seedLiked = true), picks.first { it.song.id == "plain1" }.reason)
    }

    @Test
    fun `discover limits songs per artist`() {
        val profile = TasteEngine.build(listOf(signals("seed", listens = 3)), emptyList(), emptySet())
        val batch = RelatedBatch("seed", "Seed", (1..5).map { rec("n$it", "one") })
        assertEquals(2, RecommendationEngine.discover(profile, listOf(batch), perArtist = 2).size)
    }

    // ---- daily mix ----

    private fun familiar(id: String, artist: String = "f-$id") = Recommendation(rec(id, artist), 1.0, RecommendationReason.BasedOnListening, isNew = false)

    private fun fresh(id: String, artist: String = "n-$id") = Recommendation(rec(id, artist), 1.0, RecommendationReason.SimilarTo("x", false), isNew = true)

    @Test
    fun `daily mix alternates two familiar songs with one new song`() {
        val mix =
            RecommendationEngine.dailyMix(
                familiar = (1..6).map { familiar("f$it") },
                fresh = (1..3).map { fresh("n$it") },
                size = 9,
            )
        assertEquals(listOf(false, false, true, false, false, true, false, false, true), mix.map { it.isNew })
    }

    @Test
    fun `daily mix has no duplicates and keeps going when one side runs out`() {
        val mix =
            RecommendationEngine.dailyMix(
                familiar = listOf(familiar("a"), familiar("dup"), familiar("b")),
                fresh = listOf(fresh("dup"), fresh("c")),
                size = 30,
            )
        assertEquals(listOf("a", "dup", "c", "b"), mix.map { it.song.id })
    }

    @Test
    fun `daily mix avoids the same artist twice in a row when it can`() {
        val mix =
            RecommendationEngine.dailyMix(
                familiar = listOf(familiar("a1", "same"), familiar("a2", "same"), familiar("b1", "other")),
                fresh = emptyList(),
                size = 3,
            )
        assertEquals(listOf("a1", "b1", "a2"), mix.map { it.song.id })
    }

    // ---- more like this ----

    @Test
    fun `more like this drops the seed, duplicates, disliked and often skipped songs`() {
        val profile =
            TasteEngine.build(
                listOf(signals("disliked", listens = 3), signals("skippy", listens = 1, skips = 5)),
                emptyList(),
                setOf("disliked"),
            )
        val seed = rec("seed", "seedArtist")
        val related = listOf(rec("seed"), rec("a"), rec("a"), rec("disliked"), rec("skippy"), rec("b"))
        val picks = RecommendationEngine.moreLikeThis(seed, related, profile)
        assertEquals(listOf("a", "b"), picks.map { it.song.id })
    }

    @Test
    fun `more like this names the shared artist and the seed`() {
        val profile = TasteEngine.build(listOf(signals("seed", listens = 3, liked = true)), emptyList(), emptySet())
        val seed = rec("seed", "band")
        val picks = RecommendationEngine.moreLikeThis(seed, listOf(rec("sameBand", "band"), rec("other")), profile)
        val reasons = picks.associate { it.song.id to it.reason }
        assertEquals(RecommendationReason.SameArtist("BAND"), reasons["sameBand"])
        assertEquals(RecommendationReason.SimilarTo("Title seed", seedLiked = true), reasons["other"])
    }

    @Test
    fun `more like this keeps most of the list new`() {
        val knownIds = (1..10).map { "k$it" }
        val profile = TasteEngine.build(knownIds.map { signals(it, listens = 3) }, emptyList(), emptySet())
        val related = knownIds.map { rec(it) } + (1..10).map { rec("new$it") }
        val picks = RecommendationEngine.moreLikeThis(rec("seed"), related, profile, limit = 10, maxFamiliarShare = 0.4)
        assertEquals(10, picks.size)
        assertEquals(4, picks.count { !it.isNew })
    }
}
