/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.importer

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

sealed interface ImportTarget {
    data class NewPlaylist(
        val name: String,
    ) : ImportTarget

    /** A local playlist the user can edit. Its songs and their order are never changed. */
    data class ExistingPlaylist(
        val playlistId: String,
    ) : ImportTarget
}

data class ImportProgress(
    val done: Int,
    val total: Int,
    val found: Int,
    val notFound: Int,
)

data class NotFoundTrack(
    val track: SpotifyTrack,
    val reason: NotFoundReason,
)

data class ImportSummary(
    val playlistId: String,
    val playlistName: String,
    /** Songs added to the playlist, in file order. */
    val added: Int,
    /** Matched songs that were already in the chosen playlist. */
    val alreadyInPlaylist: List<SpotifyTrack>,
    /** Tracks listed twice in the file, or two tracks that matched the same song. */
    val repeatedInFile: List<SpotifyTrack>,
    val notFound: List<NotFoundTrack>,
)

/**
 * Turns Spotify tracks into songs of a local playlist. Every song comes from a YouTube Music
 * search and passes [TrackMatcher]; a track without a confident match is reported, not replaced.
 */
class SpotifyPlaylistImporter(
    private val database: MusicDatabase,
    private val search: suspend (String) -> Result<List<SongItem>>,
    private val parallelSearches: Int = 3,
    private val pauseBetweenSearchesMs: Long = 250,
    private val retryDelayMs: Long = 1500,
) {
    /** Searches and matches every track. The result list has the same order as [tracks]. */
    suspend fun matchAll(
        tracks: List<SpotifyTrack>,
        filters: MatchFilters,
        onProgress: (ImportProgress) -> Unit = {},
    ): List<MatchResult> =
        coroutineScope {
            val semaphore = Semaphore(parallelSearches)
            val done = AtomicInteger(0)
            val found = AtomicInteger(0)
            val notFound = AtomicInteger(0)
            onProgress(ImportProgress(0, tracks.size, 0, 0))
            tracks
                .map { track ->
                    async {
                        semaphore.withPermit {
                            val result = matchOne(track, filters)
                            if (result is MatchResult.Matched) found.incrementAndGet() else notFound.incrementAndGet()
                            onProgress(ImportProgress(done.incrementAndGet(), tracks.size, found.get(), notFound.get()))
                            delay(pauseBetweenSearchesMs)
                            result
                        }
                    }
                }.awaitAll()
        }

    private suspend fun matchOne(
        track: SpotifyTrack,
        filters: MatchFilters,
    ): MatchResult {
        val query = TrackMatcher.query(track)
        var response = search(query)
        coroutineContext.ensureActive()
        if (response.isFailure) {
            delay(retryDelayMs)
            response = search(query)
            coroutineContext.ensureActive()
        }
        val candidates = response.getOrNull() ?: return MatchResult.NotFound(NotFoundReason.SearchFailed)
        return TrackMatcher.match(track, candidates, filters)
    }

    /**
     * Creates the playlist or appends to the chosen one. Existing songs keep their positions;
     * matched songs already in the playlist are skipped; new songs go at the end in file order.
     */
    suspend fun write(
        target: ImportTarget,
        tracks: List<SpotifyTrack>,
        results: List<MatchResult>,
        repeatedInFile: List<SpotifyTrack> = emptyList(),
    ): ImportSummary =
        withContext(Dispatchers.IO) {
            require(tracks.size == results.size)
            val entity =
                when (target) {
                    is ImportTarget.NewPlaylist ->
                        PlaylistEntity(
                            name = target.name,
                            bookmarkedAt = LocalDateTime.now(),
                            isEditable = true,
                        ).also { database.insert(it) }
                    is ImportTarget.ExistingPlaylist ->
                        database.playlistEntity(target.playlistId)
                            ?: throw IllegalStateException("Playlist ${target.playlistId} not found")
                }
            check(entity.isEditable) { "Playlist ${entity.id} is not editable" }

            val existing = database.playlistSongIds(entity.id).toHashSet()
            val toAdd = LinkedHashSet<String>()
            val alreadyInPlaylist = ArrayList<SpotifyTrack>()
            val repeated = ArrayList(repeatedInFile)
            val notFound = ArrayList<NotFoundTrack>()

            results.forEachIndexed { index, result ->
                val track = tracks[index]
                when (result) {
                    is MatchResult.NotFound -> notFound.add(NotFoundTrack(track, result.reason))
                    is MatchResult.Matched -> {
                        val id = result.song.id
                        when (id) {
                            in existing -> alreadyInPlaylist.add(track)
                            in toAdd -> repeated.add(track)
                            else -> {
                                database.insert(result.song.toMediaMetadata())
                                toAdd.add(id)
                            }
                        }
                    }
                }
            }
            if (toAdd.isNotEmpty()) {
                database.addSongsToPlaylist(Playlist(entity, existing.size, emptyList()), toAdd.map { it to null })
            }
            ImportSummary(
                playlistId = entity.id,
                playlistName = entity.name,
                added = toAdd.size,
                alreadyInPlaylist = alreadyInPlaylist,
                repeatedInFile = repeated,
                notFound = notFound,
            )
        }
}
