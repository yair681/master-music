/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.importer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpotifyPlaylistImporterTest {
    private lateinit var internal: InternalDatabase
    private lateinit var database: MusicDatabase
    private val queries = mutableListOf<String>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        database = MusicDatabase(internal)
    }

    @After
    fun tearDown() {
        internal.close()
    }

    private fun song(
        id: String,
        title: String,
        artist: String,
        seconds: Int,
    ) = SongItem(id = id, title = title, artists = listOf(Artist(artist, null)), duration = seconds, thumbnail = "")

    /** YouTube Music stand-in: answers by query, slower for earlier tracks so results finish out of order. */
    private fun importer(results: Map<String, List<SongItem>>, failing: Set<String> = emptySet()) =
        SpotifyPlaylistImporter(
            database = database,
            search = { query ->
                synchronized(queries) { queries.add(query) }
                delay((results.size - results.keys.indexOf(query)).coerceAtLeast(0) * 5L)
                if (query in failing) Result.failure(IOException("offline")) else Result.success(results[query].orEmpty())
            },
            pauseBetweenSearchesMs = 0,
            retryDelayMs = 0,
        )

    private val tracks =
        listOf(
            SpotifyTrack("One", listOf("Alpha"), durationMs = 180_000),
            SpotifyTrack("Two", listOf("Beta"), durationMs = 200_000),
            SpotifyTrack("Unknown Song", listOf("Nobody"), durationMs = 150_000),
            SpotifyTrack("Three", listOf("Gamma"), durationMs = 210_000),
        )

    private val searchResults =
        mapOf(
            "One Alpha" to listOf(song("y1", "One", "Alpha", 180)),
            "Two Beta" to listOf(song("y2", "Two", "Beta", 200)),
            // Only an unrelated song comes back: it must not be added.
            "Unknown Song Nobody" to listOf(song("random", "Something Else", "Someone", 150)),
            "Three Gamma" to listOf(song("y3", "Three", "Gamma", 210)),
        )

    @Test
    fun `new playlist gets matched songs in file order and reports the rest`() =
        runBlocking {
            val importer = importer(searchResults)
            val results = importer.matchAll(tracks, MatchFilters())
            val summary = importer.write(ImportTarget.NewPlaylist("From Spotify"), tracks, results)

            assertEquals(listOf("y1", "y2", "y3"), database.playlistSongIds(summary.playlistId))
            assertEquals(3, summary.added)
            assertEquals(listOf(NotFoundTrack(tracks[2], NotFoundReason.NoConfidentMatch)), summary.notFound)
            assertEquals(null, database.songEntity("random"))
            val playlist = database.playlistEntity(summary.playlistId)!!
            assertEquals("From Spotify", playlist.name)
            assertTrue(playlist.isEditable)
            assertTrue(playlist.bookmarkedAt != null)
            assertEquals(4, queries.size)
        }

    @Test
    fun `existing playlist keeps its songs and order, duplicates are skipped`() =
        runBlocking {
            val existing =
                PlaylistEntity(id = "LPmine", name = "Mine", bookmarkedAt = LocalDateTime.of(2026, 1, 1, 0, 0))
            withContext(Dispatchers.IO) {
                database.insert(existing)
                listOf("a", "y2", "c").forEach { database.insert(SongEntity(id = it, title = it)) }
                database.insert(PlaylistSongMap(playlistId = existing.id, songId = "c", position = 0))
                database.insert(PlaylistSongMap(playlistId = existing.id, songId = "y2", position = 1))
                database.insert(PlaylistSongMap(playlistId = existing.id, songId = "a", position = 2))
            }

            val importer = importer(searchResults)
            val results = importer.matchAll(tracks, MatchFilters())
            val summary = importer.write(ImportTarget.ExistingPlaylist(existing.id), tracks, results)

            assertEquals(listOf("c", "y2", "a", "y1", "y3"), database.playlistSongIds(existing.id))
            assertEquals(2, summary.added)
            assertEquals(listOf(tracks[1]), summary.alreadyInPlaylist)
            assertEquals(1, summary.notFound.size)
            assertEquals("Mine", summary.playlistName)
        }

    @Test
    fun `two tracks that match one song are added once`() =
        runBlocking {
            val sameSong = listOf(SpotifyTrack("One", listOf("Alpha"), durationMs = 180_000), SpotifyTrack("One - Remastered 2011", listOf("Alpha"), durationMs = 181_000))
            val repeatedInFile = listOf(SpotifyTrack("One", listOf("Alpha")))
            val importer =
                importer(mapOf("One Alpha" to listOf(song("y1", "One", "Alpha", 180))))
            val results = importer.matchAll(sameSong, MatchFilters())
            val summary = importer.write(ImportTarget.NewPlaylist("P"), sameSong, results, repeatedInFile)

            assertEquals(listOf("y1"), database.playlistSongIds(summary.playlistId))
            assertEquals(1, summary.added)
            assertEquals(repeatedInFile + sameSong[1], summary.repeatedInFile)
        }

    @Test
    fun `failed searches are retried once and then reported`() =
        runBlocking {
            val importer = importer(searchResults, failing = setOf("Two Beta"))
            val results = importer.matchAll(tracks, MatchFilters())
            val summary = importer.write(ImportTarget.NewPlaylist("P"), tracks, results)

            assertEquals(listOf("y1", "y3"), database.playlistSongIds(summary.playlistId))
            assertEquals(
                listOf(NotFoundReason.SearchFailed, NotFoundReason.NoConfidentMatch),
                summary.notFound.map { it.reason },
            )
            assertEquals(2, queries.count { it == "Two Beta" })
        }

    @Test
    fun `progress counts every track`() =
        runBlocking {
            val seen = mutableListOf<ImportProgress>()
            importer(searchResults).matchAll(tracks, MatchFilters()) { synchronized(seen) { seen.add(it) } }
            assertEquals(ImportProgress(4, 4, 3, 1), seen.maxBy { it.done })
        }

    @Test
    fun `nothing is written when every track is missing`() =
        runBlocking {
            val existing = PlaylistEntity(id = "LPx", name = "X", bookmarkedAt = LocalDateTime.of(2026, 1, 1, 0, 0))
            withContext(Dispatchers.IO) {
                database.insert(existing)
                database.insert(SongEntity(id = "a", title = "a"))
                database.insert(PlaylistSongMap(playlistId = existing.id, songId = "a", position = 0))
            }
            val importer = importer(emptyMap())
            val results = importer.matchAll(tracks, MatchFilters())
            val summary = importer.write(ImportTarget.ExistingPlaylist(existing.id), tracks, results)

            assertEquals(listOf("a"), database.playlistSongIds(existing.id))
            assertEquals(0, summary.added)
            assertEquals(tracks.size, summary.notFound.size)
            assertTrue(summary.notFound.all { it.reason == NotFoundReason.NoResults })
        }
}
