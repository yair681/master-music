/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.stats

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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Timestamps are stored as the local wall-clock time encoded as UTC, so SQLite must bucket them
 * without any time zone shift: a listen at 23:30 on a Sunday stays in hour 23 of a Sunday.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InsightsQueriesTest {
    private lateinit var database: InternalDatabase
    private val dao get() = database.dao

    // Saturday.
    private val now = LocalDateTime.of(2026, 11, 7, 12, 0)
    private val from = now.minusDays(30)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        dao.insert(SongEntity(id = "a", title = "A", duration = 200, liked = true, likedDate = now.minusDays(40)))
        dao.insert(SongEntity(id = "b", title = "B", duration = 0))
        dao.insert(SongEntity(id = "c", title = "C", duration = 200))

        dao.insert(ArtistEntity(id = "artist1", name = "Artist One"))
        dao.insert(ArtistEntity(id = "artist2", name = "Artist Two"))
        dao.insert(SongArtistMap(songId = "a", artistId = "artist1", position = 0))
        dao.insert(SongArtistMap(songId = "a", artistId = "artist2", position = 1))
        dao.insert(SongArtistMap(songId = "b", artistId = "artist2", position = 0))

        // Inside the last 30 days.
        dao.insert(Event(songId = "a", timestamp = LocalDateTime.of(2026, 11, 1, 23, 30), playTime = 100_000L)) // Sunday 23h
        dao.insert(Event(songId = "a", timestamp = LocalDateTime.of(2026, 11, 2, 0, 15), playTime = 50_000L)) // Monday 0h
        dao.insert(Event(songId = "b", timestamp = LocalDateTime.of(2026, 11, 6, 21, 5), playTime = 200_000L)) // Friday 21h
        dao.insert(Event(songId = "b", timestamp = LocalDateTime.of(2026, 11, 6, 21, 55), playTime = 100_000L)) // Friday 21h
        // Older.
        dao.insert(Event(songId = "c", timestamp = LocalDateTime.of(2026, 9, 15, 8, 0), playTime = 60_000L))
        dao.insert(Event(songId = "a", timestamp = LocalDateTime.of(2025, 12, 31, 23, 59), playTime = 30_000L))
        dao.insert(Event(songId = "c", timestamp = LocalDateTime.of(2025, 12, 1, 0, 0), playTime = 10_000L))

        dao.insert(
            SongFeedback(
                songId = "c",
                type = FeedbackType.SKIP,
                timestamp = LocalDateTime.of(2026, 11, 5, 10, 0),
                positionMs = 30_000L,
                durationMs = 200_000L,
            ),
        )
        dao.insert(SongFeedback(songId = "c", type = FeedbackType.SKIP, timestamp = LocalDateTime.of(2026, 1, 1, 10, 0)))
        dao.insert(SongFeedback(songId = "a", type = FeedbackType.MORE_LIKE_THIS, timestamp = LocalDateTime.of(2026, 11, 5, 10, 0)))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `hours use the stored local wall clock without a time zone shift`() =
        runBlocking {
            assertEquals(
                listOf(
                    TimeBucket(0, 1, 50_000L),
                    TimeBucket(21, 2, 300_000L),
                    TimeBucket(23, 1, 100_000L),
                ),
                dao.listeningByHour(from, now),
            )
        }

    @Test
    fun `weekdays count from Sunday as zero`() =
        runBlocking {
            assertEquals(
                listOf(
                    TimeBucket(0, 1, 100_000L),
                    TimeBucket(1, 1, 50_000L),
                    TimeBucket(5, 2, 300_000L),
                ),
                dao.listeningByWeekday(from, now),
            )
        }

    @Test
    fun `days and months are labelled by the local date`() =
        runBlocking {
            assertEquals(
                listOf(
                    DateBucket("2026-11-01", 1, 100_000L),
                    DateBucket("2026-11-02", 1, 50_000L),
                    DateBucket("2026-11-06", 2, 300_000L),
                ),
                dao.listeningByDay(from, now),
            )
            assertEquals(
                listOf(
                    DateBucket("2025-12", 2, 40_000L),
                    DateBucket("2026-09", 1, 60_000L),
                    DateBucket("2026-11", 4, 450_000L),
                ),
                dao.listeningByMonth(LocalDateTime.of(2025, 11, 30, 0, 0), now),
            )
        }

    @Test
    fun `distinct songs, artists and first listens in a period`() =
        runBlocking {
            assertEquals(2, dao.distinctSongCount(from, now))
            // Song a credits artist1 and artist2, song b artist2.
            assertEquals(2, dao.distinctArtistCount(from, now))
            // Only b was heard for the first time in the period; a and c were heard before it.
            assertEquals(1, dao.firstListenCount(from, now))
            assertEquals(3, dao.firstListenCount(LocalDateTime.of(1970, 1, 1, 0, 0), now))
        }

    @Test
    fun `skips only count SKIP feedback in the period`() =
        runBlocking {
            val skips = dao.skipSummary(from, now)
            assertEquals(1, skips.skips)
            assertEquals(0.15, skips.completionSum, 1e-9)
            assertEquals(1, skips.timedSkips)

            assertEquals(SkipSummary(0, 0.0, 0), dao.skipSummary(now, now.plusDays(1)))
        }

    @Test
    fun `song and main artist stats for a period`() =
        runBlocking {
            val songs = dao.songPeriodStats(from, now).associateBy { it.songId }
            assertEquals(setOf("a", "b"), songs.keys)
            val a = songs.getValue("a")
            assertEquals(2, a.listens)
            assertEquals(150_000L, a.playTimeMs)
            assertTrue(a.liked)
            assertEquals(0.75, a.completionSum, 1e-9)
            assertEquals(2, a.timedListens)
            val b = songs.getValue("b")
            assertEquals(0, b.timedListens)
            assertEquals(0.0, b.completionSum, 1e-9)

            assertEquals(
                listOf(
                    ArtistPeriodTime("artist2", "Artist Two", null, 2, 300_000L),
                    ArtistPeriodTime("artist1", "Artist One", null, 2, 150_000L),
                ),
                dao.mainArtistPeriodTime(from, now),
            )
        }

    @Test
    fun `repository fills every bucket and computes the taste DNA`() =
        runBlocking {
            val insights = InsightsRepository(MusicDatabase(database)).load(InsightsPeriod.LAST_30_DAYS, now)

            assertEquals(4, insights.totalListens)
            assertEquals(450_000L, insights.totalPlayTimeMs)
            assertEquals(2, insights.distinctSongs)
            assertEquals(2, insights.distinctArtists)
            assertEquals(1, insights.newSongs)
            assertEquals(1, insights.skips)

            assertEquals(24, insights.hours.size)
            assertEquals(300_000L, insights.hours[21].playTimeMs)
            assertEquals(7, insights.weekdays.size)

            assertEquals(12, insights.months.size)
            assertEquals(MonthBar(YearMonth.of(2025, 12), 2, 40_000L), insights.months.first())
            assertEquals(MonthBar(YearMonth.of(2026, 11), 4, 450_000L), insights.months.last())

            assertEquals(30, insights.days.size)
            assertEquals(LocalDate.of(2026, 10, 9), insights.days.first().date)
            assertEquals(DayBar(LocalDate.of(2026, 11, 7), 0, 0L), insights.days.last())
            assertEquals(DayBar(LocalDate.of(2026, 11, 6), 2, 300_000L), insights.days[28])

            val dna = insights.dna
            assertEquals(300.0 / 450.0, dna.topArtistShare!!, 1e-9)
            assertEquals(1.0, dna.topFiveArtistShare!!, 1e-9)
            assertEquals(0.5, dna.artistVariety!!, 1e-9)
            assertEquals(0.25, dna.discoveryRate!!, 1e-9)
            assertEquals(0.0, dna.repeatRate!!, 1e-9)
            assertEquals((0.75 + 0.15) / 3, dna.completionRate!!, 1e-9)
            assertEquals(0.2, dna.skipRate!!, 1e-9)
            assertEquals(0.5, dna.likedShare!!, 1e-9)
            assertEquals(21, dna.peakHour)
            assertEquals(5, dna.peakWeekday)
        }

    @Test
    fun `an empty period loads as empty`() =
        runBlocking {
            val insights = InsightsRepository(MusicDatabase(database)).load(InsightsPeriod.LAST_30_DAYS, now.plusYears(5))
            assertTrue(insights.isEmpty)
            assertTrue(insights.dna.isEmpty)
            assertEquals(24, insights.hours.size)
            assertTrue(insights.hours.all { it.listens == 0 })
        }
}
