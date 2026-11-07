/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

data class RecommendationFilters(
    val hideExplicit: Boolean,
    val hideVideoSongs: Boolean,
)

/** Quick picks backed by songs already in the library database, so the existing UI can show them. */
data class PersonalQuickPicks(
    val songs: List<Song>,
    val reasons: Map<String, RecommendationReason>,
)

data class DiscoverPick(
    val seed: Song,
    val recommendation: Recommendation<SongItem>,
    val relatedEndpoint: BrowseEndpoint?,
)

data class Discovery(
    val discover: List<DiscoverPick>,
    val dailyMix: List<Recommendation<SongItem>>,
)

/**
 * Glue between the local [TasteProfile], the library database and YouTube Music related lists.
 * The only network requests are the related lists of the user's own seed songs, the same
 * requests the radio already makes; the profile itself never leaves the device.
 */
@Singleton
class RecommendationRepository
    @Inject
    constructor(
        private val database: MusicDatabase,
        private val tasteProfiles: TasteProfileRepository,
    ) {
        private class CachedRelated(
            val dayKey: Long,
            val endpoint: BrowseEndpoint?,
            val songs: List<SongItem>,
        )

        private val relatedCache = HashMap<String, CachedRelated>()
        private val cacheLock = Mutex()

        fun today(): Long = LocalDate.now().toEpochDay()

        suspend fun profile(): TasteProfile = tasteProfiles.currentProfile()

        /** Enough real history for personal picks: a handful of songs the user actually listened to. */
        fun hasEnoughData(profile: TasteProfile): Boolean = profile.songs.count { it.listens > 0 } >= MIN_LISTENED_SONGS

        suspend fun quickPicks(
            profile: TasteProfile,
            filters: RecommendationFilters,
            limit: Int = QUICK_PICKS_LIMIT,
        ): PersonalQuickPicks {
            val (localSongs, recSongs) = localCandidates(profile, filters)
            val recentlyPlayed = recentlyPlayedIds()
            val picks = RecommendationEngine.quickPicks(profile, recSongs, recentlyPlayed, today(), limit = limit)
            return PersonalQuickPicks(
                songs = picks.mapNotNull { localSongs[it.song.id] },
                reasons = picks.associate { it.song.id to it.reason },
            )
        }

        /**
         * Daily Discover (new songs, one card each) and the Daily Mix (familiar songs that are not
         * already in [shownQuickPicks], interleaved with further new songs).
         */
        suspend fun discovery(
            profile: TasteProfile,
            filters: RecommendationFilters,
            shownQuickPicks: Set<String>,
        ): Discovery {
            val day = today()
            val seeds = RecommendationEngine.seeds(profile, day)
            val seedSongs = database.getSongsByIds(seeds.map { it.songId }).associateBy { it.id }
            val fetched =
                coroutineScope {
                    seeds
                        .mapNotNull { seedSongs[it.songId] }
                        .map { seed -> async(Dispatchers.IO) { seed to related(seed.id, day, filters) } }
                        .awaitAll()
                }
            val batches =
                fetched.map { (seed, related) ->
                    RelatedBatch(seed.id, seed.title, related.songs.map { it.toRecSong() })
                }
            val endpoints = fetched.associate { (seed, related) -> seed.id to related.endpoint }

            val allNew = RecommendationEngine.discover(profile, batches, limit = DISCOVER_POOL)
            val cards =
                allNew.take(DISCOVER_CARDS).mapNotNull { rec ->
                    val seed = rec.seedId?.let { seedSongs[it] } ?: return@mapNotNull null
                    DiscoverPick(seed, rec, endpoints[seed.id])
                }
            val cardIds = cards.mapTo(HashSet()) { it.recommendation.song.id }
            val mixFresh = allNew.filter { it.song.id !in cardIds }.ifEmpty { allNew }

            val (_, recSongs) = localCandidates(profile, filters)
            val familiarPool =
                RecommendationEngine.quickPicks(profile, recSongs, recentlyPlayedIds(), day, limit = MIX_FAMILIAR_POOL)
            val mixFamiliar = familiarPool.filter { it.song.id !in shownQuickPicks }.ifEmpty { familiarPool }
            val mix = RecommendationEngine.dailyMix(mixFamiliar, mixFresh, size = DAILY_MIX_SIZE)
            return Discovery(discover = cards, dailyMix = if (mix.size >= DAILY_MIX_MIN_SIZE) mix else emptyList())
        }

        suspend fun moreLikeThis(
            seed: MediaMetadata,
            filters: RecommendationFilters,
        ): List<Recommendation<SongItem>> {
            val profile = profile()
            val related = related(seed.id, today(), filters)
            val seedRec = RecSong(seed.id, seed.title, seed.artists.map { RecArtist(it.id, it.name) }, Unit)
            return RecommendationEngine.moreLikeThis(seedRec, related.songs.map { it.toRecSong() }, profile)
        }

        /** Next page of the personal radio, seeded by [seedIds] (the first one names the station). */
        suspend fun radioPage(
            station: MediaMetadata,
            seedIds: List<String>,
            exclude: Set<String>,
            filters: RecommendationFilters,
        ): List<Recommendation<SongItem>> {
            val profile = profile()
            val day = today()
            val batches =
                coroutineScope {
                    seedIds.distinct().map { id -> async(Dispatchers.IO) { related(id, day, filters).songs.map { it.toRecSong() } } }.awaitAll()
                }
            val stationRec = RecSong(station.id, station.title, station.artists.map { RecArtist(it.id, it.name) }, Unit)
            return RecommendationEngine.radioPage(stationRec, batches, profile, exclude)
        }

        /** SmartQueue: refines items autoplay is about to append (see [RecommendationEngine.refineQueue]). */
        suspend fun <T> refineQueue(
            page: List<RecSong<T>>,
            alreadyQueued: Set<String>,
        ): List<T> = RecommendationEngine.refineQueue(page, profile(), alreadyQueued).map { it.payload }

        private suspend fun recentlyPlayedIds(): Set<String> =
            withContext(Dispatchers.IO) {
                database.songIdsPlayedSince(LocalDateTime.now().minusHours(RECENT_REPEAT_HOURS)).toSet()
            }

        private suspend fun localCandidates(
            profile: TasteProfile,
            filters: RecommendationFilters,
        ): Pair<Map<String, Song>, Map<String, RecSong<SongItem>>> =
            withContext(Dispatchers.IO) {
                val ids = profile.songs.asSequence().filter { it.score > 0 && !it.disliked }.take(LOCAL_POOL).map { it.songId }.toList()
                val songs =
                    ids.chunked(SQL_CHUNK).flatMap { database.getSongsByIds(it) }.filter { song ->
                        !song.song.isLocal &&
                            !song.song.isEpisode &&
                            !(filters.hideExplicit && song.song.explicit) &&
                            !(filters.hideVideoSongs && song.song.isVideo)
                    }
                songs.associateBy { it.id } to songs.associate { it.id to it.toRecSong() }
            }

        /** YouTube Music's related songs for [songId], cached for the day and filtered by [filters]. */
        suspend fun relatedSongs(
            songId: String,
            filters: RecommendationFilters,
        ): List<SongItem> = related(songId, today(), filters).songs

        private class Related(
            val endpoint: BrowseEndpoint?,
            val songs: List<SongItem>,
        )

        private suspend fun related(
            songId: String,
            day: Long,
            filters: RecommendationFilters,
        ): Related {
            val cached = cacheLock.withLock { relatedCache[songId]?.takeIf { it.dayKey == day } }
            val entry =
                cached ?: run {
                    val endpoint = YouTube.next(WatchEndpoint(videoId = songId)).getOrNull()?.relatedEndpoint
                    val songs = endpoint?.let { YouTube.related(it).getOrNull()?.songs }.orEmpty()
                    CachedRelated(day, endpoint, songs).also { fresh ->
                        // Do not remember failures, so the next refresh can try again.
                        if (songs.isNotEmpty()) cacheLock.withLock { relatedCache[songId] = fresh }
                    }
                }
            return Related(
                entry.endpoint,
                entry.songs.filter { item ->
                    !item.isEpisode &&
                        !(filters.hideExplicit && item.explicit) &&
                        !(filters.hideVideoSongs && item.isVideoSong)
                },
            )
        }

        companion object {
            const val MIN_LISTENED_SONGS = 5
            const val QUICK_PICKS_LIMIT = 20
            const val DISCOVER_CARDS = 10
            const val DISCOVER_POOL = 40
            const val MIX_FAMILIAR_POOL = 60
            const val DAILY_MIX_SIZE = 30
            const val DAILY_MIX_MIN_SIZE = 8
            private const val LOCAL_POOL = 300
            private const val SQL_CHUNK = 500
            private const val RECENT_REPEAT_HOURS = 3L
        }
    }

internal fun SongItem.toRecSong(): RecSong<SongItem> = RecSong(id, title, artists.map { RecArtist(it.id, it.name) }, this)

internal fun Song.toRecSong(): RecSong<SongItem> =
    RecSong(
        id = id,
        title = title,
        artists = artists.map { RecArtist(it.id, it.name) },
        payload =
            SongItem(
                id = id,
                title = title,
                artists = artists.map { Artist(name = it.name, id = it.id) },
                duration = song.duration.takeIf { it > 0 },
                thumbnail = thumbnailUrl.orEmpty(),
                explicit = song.explicit,
            ),
    )
