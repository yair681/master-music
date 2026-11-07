/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.FeedbackType
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.db.entities.SongFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TasteProfileQueriesTest {
    private lateinit var database: InternalDatabase
    private val dao get() = database.dao
    private val now = LocalDateTime.of(2026, 11, 1, 12, 0)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        dao.insert(SongEntity(id = "a", title = "A", duration = 200))
        dao.insert(SongEntity(id = "liked", title = "Liked", duration = 180, liked = true, likedDate = now.minusDays(2)))
        dao.insert(SongEntity(id = "untouched", title = "Untouched", duration = 180))
        dao.insert(SongEntity(id = "episode", title = "Episode", duration = 1800, isEpisode = true))
        dao.insert(SongEntity(id = "disliked", title = "Disliked", duration = 180))

        dao.insert(ArtistEntity(id = "artist1", name = "Artist One"))
        dao.insert(ArtistEntity(id = "artist2", name = "Artist Two"))
        dao.insert(SongArtistMap(songId = "a", artistId = "artist1", position = 0))
        dao.insert(SongArtistMap(songId = "untouched", artistId = "artist2", position = 0))

        dao.insert(Event(songId = "a", timestamp = now.minusDays(1), playTime = 200_000L))
        dao.insert(Event(songId = "a", timestamp = now.minusDays(10), playTime = 100_000L))
        dao.insert(Event(songId = "a", timestamp = now.minusDays(60), playTime = 200_000L))
        dao.insert(Event(songId = "a", timestamp = now.minusDays(400), playTime = 200_000L))
        dao.insert(Event(songId = "episode", timestamp = now.minusDays(1), playTime = 600_000L))

        dao.insert(
            SongFeedback(
                songId = "a",
                type = FeedbackType.SKIP,
                timestamp = now.minusDays(3),
                positionMs = 20_000L,
                durationMs = 200_000L,
            ),
        )
        dao.insert(SongFeedback(songId = "a", type = FeedbackType.MORE_LIKE_THIS, timestamp = now.minusDays(2)))
        dao.insert(SongFeedback(songId = "disliked", type = FeedbackType.DISLIKE, timestamp = now.minusDays(1)))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `signals aggregate listens, recency buckets, completion and feedback`() =
        runBlocking {
            val signals =
                dao
                    .tasteSongSignals(recentFrom = now.minusDays(30), midFrom = now.minusDays(180))
                    .first()
                    .associateBy { it.songId }

            assertEquals(setOf("a", "liked", "disliked"), signals.keys)

            val a = signals.getValue("a")
            assertEquals(4, a.listens)
            assertEquals(700_000L, a.playMs)
            assertEquals(2, a.recentListens)
            assertEquals(1, a.midListens)
            assertEquals(3.5, a.completionSum, 1e-9)
            assertEquals(4, a.timedListens)
            assertEquals(1, a.skips)
            assertEquals(0.1, a.skipCompletionSum, 1e-9)
            assertEquals(1, a.timedSkips)
            assertEquals(20_000L, a.skipPositionMs)
            assertEquals(1, a.moreLikeThis)
            assertEquals(now.minusDays(1), a.lastPlayed)

            val liked = signals.getValue("liked")
            assertTrue(liked.liked)
            assertEquals(0, liked.listens)
            assertNull(liked.lastPlayed)
        }

    @Test
    fun `artist links only cover songs with signals`() =
        runBlocking {
            val links = dao.tasteArtistLinks().first()
            assertEquals(listOf(SongArtistLink("a", "artist1", "Artist One", 0)), links)
        }

    @Test
    fun `repository builds a ranked profile from the database`() =
        runBlocking {
            val profile = TasteProfileRepository(MusicDatabase(database)).currentProfile(now)

            assertEquals(listOf("a", "liked", "disliked"), profile.songs.map { it.songId })
            assertTrue(profile.song("disliked")!!.disliked)
            assertEquals(listOf("artist1"), profile.favoriteArtists.map { it.artistId })
            assertEquals(4, profile.totalListens)
            assertEquals(700_000L + 20_000L, profile.totalListeningTimeMs)
            assertEquals(now.minusDays(1), profile.song("a")!!.lastPlayed)
        }
}
