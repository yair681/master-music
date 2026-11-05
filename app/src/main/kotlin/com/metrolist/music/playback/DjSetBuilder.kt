/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.MediaItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.filterExplicit
import com.metrolist.music.extensions.filterVideoSongs
import com.metrolist.music.extensions.toMediaItem

/**
 * Picks songs for Master DJ only from what the app already knows: liked, library,
 * downloaded and previously played songs. Recently played tracks are pushed to the
 * back of the line, and nothing already used in the current set is picked again.
 */
class DjSetBuilder(
    private val database: MusicDatabase,
) {
    suspend fun nextBatch(
        size: Int,
        excludeIds: Set<String>,
        lastId: String?,
        hideExplicit: Boolean,
        hideVideos: Boolean,
    ): List<MediaItem> {
        val pool =
            database
                .djCandidateSongs(CANDIDATE_POOL)
                .filterExplicit(hideExplicit)
                .filterVideoSongs(hideVideos)
                .filter { it.id !in excludeIds }
        if (pool.isEmpty()) return emptyList()

        val recent = database.recentlyPlayedSongIds(RECENT_WINDOW).toHashSet()
        val (stale, fresh) = pool.partition { it.id in recent }
        val ordered = (fresh + stale.shuffled())

        val picked = ArrayList<MediaItem>(size)
        var previousId = lastId
        var previousArtist: String? = null
        val deferred = ArrayList<Song>()
        for (song in ordered) {
            if (picked.size >= size) break
            if (song.id == previousId) continue
            val artist = song.artists.firstOrNull()?.id
            if (artist != null && artist == previousArtist) {
                deferred.add(song)
                continue
            }
            picked.add(song.toMediaItem())
            previousId = song.id
            previousArtist = artist
        }
        for (song in deferred) {
            if (picked.size >= size) break
            if (song.id == previousId) continue
            picked.add(song.toMediaItem())
            previousId = song.id
        }
        return picked
    }

    private companion object {
        const val CANDIDATE_POOL = 80
        const val RECENT_WINDOW = 40
    }
}
