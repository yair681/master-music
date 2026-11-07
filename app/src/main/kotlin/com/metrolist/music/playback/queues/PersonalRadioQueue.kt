/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.taste.RecommendationFilters
import com.metrolist.music.taste.RecommendationRepository

/**
 * Endless radio built on the device: every page is YouTube Music's related songs for the station
 * song and the latest picks, re-ranked by the local taste profile. The profile is read again for each
 * page, so likes, dislikes and skips during the session steer what comes next.
 */
class PersonalRadioQueue(
    private val station: MediaMetadata,
    private val title: String,
    private val repository: RecommendationRepository,
    private val filters: () -> RecommendationFilters,
) : Queue {
    override val preloadItem: MediaMetadata = station

    private val played = LinkedHashSet<String>()
    private val lastPicks = ArrayList<String>()
    private var emptyPages = 0

    override suspend fun getInitialStatus(): Queue.Status {
        val page = loadPage()
        return Queue.Status(
            title = title,
            items = listOf(station.toMediaItem()) + page,
            mediaItemIndex = 0,
        )
    }

    override fun hasNextPage(): Boolean = emptyPages < MAX_EMPTY_PAGES

    override suspend fun nextPage(): List<MediaItem> = loadPage()

    private suspend fun loadPage(): List<MediaItem> {
        played += station.id
        val seeds = listOf(station.id) + lastPicks.takeLast(SEEDS_FROM_LAST_PAGE)
        val picks =
            runCatching { repository.radioPage(station, seeds, played, filters()) }
                .getOrDefault(emptyList())
        if (picks.isEmpty()) {
            emptyPages++
            return emptyList()
        }
        emptyPages = 0
        lastPicks.clear()
        picks.forEach {
            played += it.song.id
            lastPicks += it.song.id
        }
        return picks.map { it.song.payload.toMediaItem() }
    }

    private companion object {
        const val SEEDS_FROM_LAST_PAGE = 2
        const val MAX_EMPTY_PAGES = 2
    }
}
