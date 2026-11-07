/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.FeedbackType
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.db.entities.SongFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SongFeedbackDaoTest {
    private lateinit var database: InternalDatabase
    private val dao get() = database.dao
    private val t0 = LocalDateTime.of(2026, 11, 1, 12, 0)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao.insert(SongEntity(id = "a", title = "A"))
        dao.insert(SongEntity(id = "b", title = "B"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun feedback(
        songId: String,
        type: FeedbackType,
        minutesAfterStart: Long,
    ) = dao.insert(SongFeedback(songId = songId, type = type, timestamp = t0.plusMinutes(minutesAfterStart)))

    @Test
    fun `no feedback means not disliked`() =
        runBlocking {
            assertFalse(dao.isDislikedNow("a"))
            assertEquals(emptyList<String>(), dao.dislikedSongIds().first())
        }

    @Test
    fun `dislike marks only that song`() =
        runBlocking {
            feedback("a", FeedbackType.DISLIKE, 1)
            assertTrue(dao.isDislikedNow("a"))
            assertFalse(dao.isDislikedNow("b"))
            assertEquals(listOf("a"), dao.dislikedSongIds().first())
            assertTrue(dao.isDisliked("a").first())
        }

    @Test
    fun `latest dislike entry wins`() =
        runBlocking {
            feedback("a", FeedbackType.DISLIKE, 1)
            feedback("a", FeedbackType.UNDISLIKE, 2)
            assertFalse(dao.isDislikedNow("a"))
            feedback("a", FeedbackType.DISLIKE, 3)
            assertTrue(dao.isDislikedNow("a"))
        }

    @Test
    fun `skips and more like this do not change dislike state`() =
        runBlocking {
            feedback("a", FeedbackType.DISLIKE, 1)
            feedback("a", FeedbackType.SKIP, 2)
            feedback("a", FeedbackType.MORE_LIKE_THIS, 3)
            assertTrue(dao.isDislikedNow("a"))
        }

    @Test
    fun `liking after a dislike clears it from any screen`() =
        runBlocking {
            feedback("a", FeedbackType.DISLIKE, 1)
            dao.update(SongEntity(id = "a", title = "A", liked = true, likedDate = t0.plusMinutes(5)))
            assertFalse(dao.isDislikedNow("a"))
        }

    @Test
    fun `an old like does not cancel a newer dislike`() =
        runBlocking {
            dao.update(SongEntity(id = "a", title = "A", liked = true, likedDate = t0))
            feedback("a", FeedbackType.DISLIKE, 5)
            assertTrue(dao.isDislikedNow("a"))
        }

    @Test
    fun `feedback is counted by type`() =
        runBlocking {
            feedback("a", FeedbackType.SKIP, 1)
            feedback("a", FeedbackType.SKIP, 2)
            feedback("a", FeedbackType.MORE_LIKE_THIS, 3)
            feedback("b", FeedbackType.SKIP, 4)
            assertEquals(2, dao.feedbackCount("a", FeedbackType.SKIP.name))
            assertEquals(1, dao.feedbackCount("a", FeedbackType.MORE_LIKE_THIS.name))
            assertEquals(0, dao.feedbackCount("a", FeedbackType.DISLIKE.name))
        }

    @Test
    fun `skip keeps position and duration`() =
        runBlocking {
            dao.insert(
                SongFeedback(
                    songId = "a",
                    type = FeedbackType.SKIP,
                    timestamp = t0,
                    positionMs = 12_000L,
                    durationMs = 200_000L,
                ),
            )
            database.openHelper.readableDatabase
                .query("SELECT type, positionMs, durationMs FROM song_feedback WHERE songId = 'a'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("SKIP", cursor.getString(0))
                    assertEquals(12_000L, cursor.getLong(1))
                    assertEquals(200_000L, cursor.getLong(2))
                }
        }
}
