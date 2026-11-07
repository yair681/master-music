/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The local taste profile. It is derived from the listening history, likes and feedback that are
 * already stored in the database, so nothing new is persisted and nothing leaves the device.
 * Any change to those tables (a new listen, a skip, a like or a dislike) rebuilds the profile,
 * and older listens weigh less, so the profile follows the user when their taste changes.
 */
@Singleton
class TasteProfileRepository
    @Inject
    constructor(
        private val database: MusicDatabase,
    ) {
        @OptIn(FlowPreview::class)
        fun profile(now: LocalDateTime = LocalDateTime.now()): Flow<TasteProfile> =
            combine(
                database.tasteSongSignals(
                    recentFrom = now.minusDays(TasteEngine.RECENT_DAYS),
                    midFrom = now.minusDays(TasteEngine.MID_DAYS),
                ),
                database.tasteArtistLinks(),
                database.dislikedSongIds(),
            ) { signals, links, disliked ->
                Triple(signals, links, disliked)
            }.distinctUntilChanged()
                // Playback touches the song table often; rebuild at most a few times per second.
                .debounce(REBUILD_DEBOUNCE_MS)
                .conflate()
                .map { (signals, links, disliked) -> TasteEngine.build(signals, links, disliked.toSet()) }
                .flowOn(Dispatchers.Default)

        suspend fun currentProfile(now: LocalDateTime = LocalDateTime.now()): TasteProfile = profile(now).first()

        private companion object {
            const val REBUILD_DEBOUNCE_MS = 300L
        }
    }
