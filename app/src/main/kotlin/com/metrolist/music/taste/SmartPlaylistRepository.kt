/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.MoodAndGenres
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Why a smart playlist could not be made. Shown to the user as is; nothing is faked instead. */
sealed interface SmartPlaylistFailure {
    /** Nothing in the text was recognized. */
    data object NotUnderstood : SmartPlaylistFailure

    data class ArtistNotFound(
        val name: String,
    ) : SmartPlaylistFailure

    data class CategoryNotFound(
        val words: List<String>,
    ) : SmartPlaylistFailure

    /** Liked / familiar / forgotten / personal requests without enough listening history. */
    data object NotEnoughHistory : SmartPlaylistFailure

    data object NoMatchingSongs : SmartPlaylistFailure

    data object NetworkError : SmartPlaylistFailure
}

sealed interface SmartPlaylistNote {
    data class ShorterThanRequested(
        val actualMs: Long,
        val targetMs: Long,
    ) : SmartPlaylistNote

    data class FewerSongsThanRequested(
        val actual: Int,
        val requested: Int,
    ) : SmartPlaylistNote

    /** Words that could not be used together with the rest of the request. */
    data class IgnoredWords(
        val words: List<String>,
    ) : SmartPlaylistNote
}

sealed interface SmartPlaylistResult {
    data class Ready(
        val title: String,
        val songs: List<Recommendation<SongItem>>,
        val totalMs: Long,
        val notes: List<SmartPlaylistNote>,
    ) : SmartPlaylistResult

    data class Unavailable(
        val reason: SmartPlaylistFailure,
    ) : SmartPlaylistResult
}

/**
 * Builds a playlist from a [PlaylistRequest]. Every song is a real YouTube Music song or a song from
 * the local database; ranking re-uses [RecommendationEngine]. The only network requests go to
 * YouTube Music (artist search and page, related lists, moods and genres, playlists).
 */
@Singleton
class SmartPlaylistRepository
    @Inject
    constructor(
        private val database: MusicDatabase,
        private val recommendations: RecommendationRepository,
    ) {
        private class NetworkFailure : Exception()

        private var categoryCache: List<MoodAndGenres.Item>? = null
        private val categoryLock = Mutex()

        /** YouTube Music moods and genres (cached for the app session once loaded). */
        suspend fun categories(): Result<List<MoodAndGenres.Item>> =
            categoryLock.withLock {
                categoryCache?.let { return@withLock Result.success(it) }
                withContext(Dispatchers.IO) { YouTube.moodAndGenres() }
                    .map { sections -> sections.flatMap { it.items }.distinctBy { it.title } }
                    .onSuccess { if (it.isNotEmpty()) categoryCache = it }
            }

        suspend fun generate(
            request: PlaylistRequest,
            filters: RecommendationFilters,
            now: LocalDateTime = LocalDateTime.now(),
        ): SmartPlaylistResult =
            withContext(Dispatchers.IO) {
                try {
                    build(request, filters, now)
                } catch (e: NetworkFailure) {
                    SmartPlaylistResult.Unavailable(SmartPlaylistFailure.NetworkError)
                }
            }

        private fun unavailable(reason: SmartPlaylistFailure) = SmartPlaylistResult.Unavailable(reason)

        private suspend fun build(
            request: PlaylistRequest,
            filters: RecommendationFilters,
            now: LocalDateTime,
        ): SmartPlaylistResult {
            if (!request.isRecognized) return unavailable(SmartPlaylistFailure.NotUnderstood)
            val profile = recommendations.profile()
            val notes = ArrayList<SmartPlaylistNote>()

            if (request.usesLocalHistory) {
                val enough =
                    when {
                        request.likedOnly -> profile.songs.any { it.liked && !it.disliked }
                        request.forgotten -> profile.songs.any { it.lastPlayed != null }
                        else -> profile.songs.any { it.listens > 0 }
                    }
                if (!enough) return unavailable(SmartPlaylistFailure.NotEnoughHistory)
            }

            var artistName = request.artist
            var category: MoodAndGenres.Item? = null
            if (request.category != null) {
                val all = categories().getOrElse { throw NetworkFailure() }
                category = all.firstOrNull { it.title == request.category }
                    ?: return unavailable(SmartPlaylistFailure.CategoryNotFound(listOf(request.category)))
            } else if (request.artist != null && request.artistAmbiguous && localArtist(profile, request.artist) == null) {
                // "פלייליסט של רוק" names a genre, not an artist.
                val genre = categories().getOrNull()?.firstOrNull { PlaylistBuilder.isWholeCategory(request.artist, it.title) }
                if (genre != null) {
                    category = genre
                    artistName = null
                }
            }

            val ranked: List<Recommendation<SongItem>>
            var perArtist: Int? = PlaylistBuilder.MAX_PER_ARTIST
            val name = artistName
            val chosenCategory = category
            when {
                name != null -> {
                    if (request.categoryWords.isNotEmpty()) notes += SmartPlaylistNote.IgnoredWords(request.categoryWords)
                    val artist = resolveArtist(name, profile) ?: return unavailable(SmartPlaylistFailure.ArtistNotFound(name))
                    if (request.artistMode == ArtistMode.ONLY) perArtist = null
                    ranked = artistCandidates(artist, request, profile, filters)
                }
                chosenCategory != null -> ranked = categoryCandidates(chosenCategory, profile, filters)
                request.usesLocalHistory -> {
                    if (request.categoryWords.isNotEmpty()) notes += SmartPlaylistNote.IgnoredWords(request.categoryWords)
                    ranked = localCandidates(request, profile, filters, now)
                }
                request.categoryWords.isNotEmpty() -> {
                    val all = categories().getOrElse { throw NetworkFailure() }
                    val title =
                        PlaylistBuilder.matchCategory(request.categoryWords, all.map { it.title })
                            ?: return unavailable(SmartPlaylistFailure.CategoryNotFound(request.categoryWords))
                    ranked = categoryCandidates(all.first { it.title == title }, profile, filters)
                }
                else -> {
                    if (profile.songs.none { it.listens > 0 }) return unavailable(SmartPlaylistFailure.NotEnoughHistory)
                    ranked = personalCandidates(request, profile, filters)
                }
            }

            val usable =
                PlaylistBuilder
                    .applyFlags(PlaylistBuilder.withoutDisliked(ranked, profile), request, profile, now)
                    .filter { allowed(it.song.payload, filters) }
            if (usable.isEmpty()) return unavailable(SmartPlaylistFailure.NoMatchingSongs)

            val filled =
                PlaylistBuilder.fillToDuration(
                    ranked = usable,
                    durationMs = { item -> item.duration?.let { it * 1000L } },
                    targetMs = request.targetMs,
                    targetCount = request.songCount,
                    perArtist = perArtist,
                )
            if (filled.songs.isEmpty()) return unavailable(SmartPlaylistFailure.NoMatchingSongs)

            val target = request.targetMs
            if (target != null && filled.totalMs < target - PlaylistBuilder.TOLERANCE_MS) {
                notes += SmartPlaylistNote.ShorterThanRequested(filled.totalMs, target)
            }
            val count = request.songCount
            if (count != null && filled.songs.size < count) {
                notes += SmartPlaylistNote.FewerSongsThanRequested(filled.songs.size, count)
            }
            return SmartPlaylistResult.Ready(
                title = request.category ?: request.text.take(TITLE_MAX),
                songs = filled.songs,
                totalMs = filled.totalMs,
                notes = notes,
            )
        }

        private fun allowed(
            item: SongItem,
            filters: RecommendationFilters,
        ): Boolean =
            !item.isEpisode &&
                !(filters.hideExplicit && item.explicit) &&
                !(filters.hideVideoSongs && item.isVideoSong)

        private class ResolvedArtist(
            val browseId: String?,
            val name: String,
            val localId: String?,
        )

        private fun localArtist(
            profile: TasteProfile,
            name: String,
        ): ArtistTaste? {
            val lower = name.trim().lowercase()
            return profile.artists.firstOrNull { it.name.lowercase() == lower && !it.isDisliked() }
                ?: profile.artists
                    .filter { lower.startsWith(it.name.lowercase() + " ") && !it.isDisliked() }
                    .maxByOrNull { it.name.length }
        }

        private fun ArtistTaste.isDisliked() = score <= RecommendationEngine.BLOCKED_ARTIST_SCORE

        private suspend fun resolveArtist(
            name: String,
            profile: TasteProfile,
        ): ResolvedArtist? {
            val local = localArtist(profile, name)
            if (local != null && local.artistId.startsWith("UC")) return ResolvedArtist(local.artistId, local.name, local.artistId)
            val query = local?.name ?: name
            val found =
                YouTube
                    .search(query, YouTube.SearchFilter.FILTER_ARTIST)
                    .getOrElse { if (local != null) null else throw NetworkFailure() }
                    ?.items
                    ?.filterIsInstance<ArtistItem>()
                    ?.firstOrNull()
            return when {
                found != null -> ResolvedArtist(found.id, found.title, local?.artistId)
                local != null -> ResolvedArtist(null, local.name, local.artistId)
                else -> null
            }
        }

        private suspend fun youtubeArtistSongs(
            artist: ResolvedArtist,
            filters: RecommendationFilters,
        ): List<SongItem> {
            val browseId = artist.browseId ?: return emptyList()
            val page = YouTube.artist(browseId).getOrElse { throw NetworkFailure() }
            val onPage = page.sections.flatMap { section -> section.items.filterIsInstance<SongItem>() }
            val more =
                page.sections
                    .firstOrNull { section -> section.moreEndpoint != null && section.items.any { it is SongItem } }
                    ?.moreEndpoint
                    ?.let { YouTube.artistItems(it).getOrNull()?.items?.filterIsInstance<SongItem>() }
                    .orEmpty()
            return (onPage + more).distinctBy { it.id }.filter { allowed(it, filters) }
        }

        /** Songs from the local history whose artists include [artist]. */
        private suspend fun localArtistSongs(
            artist: ResolvedArtist,
            profile: TasteProfile,
            filters: RecommendationFilters,
        ): List<Song> {
            val ids = setOfNotNull(artist.browseId, artist.localId)
            val lower = artist.name.lowercase()
            return loadSongs(profile.songs.filter { !it.disliked }.map { it.songId }, filters).filter { song ->
                song.artists.any { it.id in ids || it.name.lowercase() == lower }
            }
        }

        private suspend fun artistCandidates(
            artist: ResolvedArtist,
            request: PlaylistRequest,
            profile: TasteProfile,
            filters: RecommendationFilters,
        ): List<Recommendation<SongItem>> {
            val ownSongs =
                (youtubeArtistSongs(artist, filters).map { it.toRecSong() } + localArtistSongs(artist, profile, filters).map { it.toRecSong() })
                    .distinctBy { it.id }
            if (ownSongs.isEmpty()) return emptyList()
            val seed = RecSong("", artist.name, listOf(RecArtist(artist.browseId ?: artist.localId, artist.name)), Unit)

            if (request.artistMode == ArtistMode.ONLY) {
                return RecommendationEngine.moreLikeThis(
                    seed = seed,
                    related = ownSongs,
                    profile = profile,
                    limit = PlaylistBuilder.MAX_SONGS,
                    perArtist = Int.MAX_VALUE,
                    maxFamiliarShare = 1.0,
                )
            }

            val related =
                coroutineScope {
                    ownSongs
                        .take(STYLE_SEEDS)
                        .map { song -> async { recommendations.relatedSongs(song.id, filters).map { it.toRecSong() } } }
                        .awaitAll()
                }
            val familiarShare = if (request.usesLocalHistory) 1.0 else STYLE_FAMILIAR_SHARE
            return RecommendationEngine
                .radioPage(seed, listOf(ownSongs) + related, profile, emptySet(), PlaylistBuilder.MAX_SONGS, familiarShare)
                .map { rec ->
                    if (rec.reason is RecommendationReason.SimilarTo) rec.copy(reason = RecommendationReason.StyleOf(artist.name)) else rec
                }
        }

        private suspend fun categoryCandidates(
            category: MoodAndGenres.Item,
            profile: TasteProfile,
            filters: RecommendationFilters,
        ): List<Recommendation<SongItem>> {
            val page = YouTube.browse(category.endpoint.browseId, category.endpoint.params).getOrElse { throw NetworkFailure() }
            val playlists =
                page.items
                    .flatMap { it.items }
                    .filterIsInstance<PlaylistItem>()
                    .distinctBy { it.id }
                    .take(CATEGORY_PLAYLISTS)
            if (playlists.isEmpty()) return emptyList()
            val lists =
                coroutineScope {
                    playlists.map { playlist -> async { YouTube.playlist(playlist.id).getOrNull()?.songs } }.awaitAll()
                }
            if (lists.all { it == null }) throw NetworkFailure()

            val interleaved = ArrayList<RecSong<SongItem>>()
            val nonNull = lists.map { it.orEmpty().filter { song -> allowed(song, filters) } }
            val longest = nonNull.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) for (list in nonNull) list.getOrNull(i)?.let { interleaved += it.toRecSong() }

            val refined = RecommendationEngine.refineQueue(interleaved, profile, emptySet())
            return refined.mapIndexed { index, song ->
                val taste = profile.song(song.id)
                Recommendation(
                    song = song,
                    score = (refined.size - index).toDouble(),
                    reason = if (taste?.liked == true) RecommendationReason.Liked else RecommendationReason.FromCategory(category.title),
                    isNew = taste == null || taste.listens == 0,
                )
            }
        }

        private suspend fun localCandidates(
            request: PlaylistRequest,
            profile: TasteProfile,
            filters: RecommendationFilters,
            now: LocalDateTime,
        ): List<Recommendation<SongItem>> {
            val ids =
                profile.songs
                    .asSequence()
                    .filter { !it.disliked }
                    .filter { !request.likedOnly || it.liked }
                    .filter { !request.forgotten || PlaylistBuilder.isForgotten(it, now) }
                    .filter { request.familiarity != Familiarity.FAMILIAR_ONLY || it.listens > 0 }
                    .map { it.songId }
                    .toList()
            val songs = loadSongs(ids, filters).associate { it.id to it.toRecSong() }
            val picks =
                RecommendationEngine.quickPicks(
                    profile = profile,
                    songs = songs,
                    recentlyPlayed = emptySet(),
                    dayKey = recommendations.today(),
                    limit = PlaylistBuilder.MAX_SONGS,
                    perArtist = Int.MAX_VALUE,
                )
            if (!request.forgotten) return picks
            return picks.map { rec ->
                val last = profile.song(rec.song.id)?.lastPlayed ?: return@map rec
                rec.copy(reason = RecommendationReason.NotPlayedFor(PlaylistBuilder.daysSince(last, now)))
            }
        }

        /** Requests with only a length or "new songs": the user's own mix, like the Daily Mix. */
        private suspend fun personalCandidates(
            request: PlaylistRequest,
            profile: TasteProfile,
            filters: RecommendationFilters,
        ): List<Recommendation<SongItem>> {
            val day = recommendations.today()
            val seeds = RecommendationEngine.seeds(profile, day, count = PERSONAL_SEEDS)
            val seedSongs = database.getSongsByIds(seeds.map { it.songId }).associateBy { it.id }
            val batches =
                coroutineScope {
                    seeds
                        .mapNotNull { seedSongs[it.songId] }
                        .map { seed ->
                            async {
                                RelatedBatch(seed.id, seed.title, recommendations.relatedSongs(seed.id, filters).map { it.toRecSong() })
                            }
                        }.awaitAll()
                }
            val fresh = RecommendationEngine.discover(profile, batches, limit = PlaylistBuilder.MAX_SONGS, perArtist = PlaylistBuilder.MAX_PER_ARTIST)
            if (request.familiarity == Familiarity.NEW_ONLY) return fresh
            val known = loadSongs(profile.songs.filter { it.score > 0 && !it.disliked }.take(LOCAL_POOL).map { it.songId }, filters)
            val familiar =
                RecommendationEngine.quickPicks(
                    profile,
                    known.associate { it.id to it.toRecSong() },
                    recentlyPlayed = emptySet(),
                    dayKey = day,
                    limit = PlaylistBuilder.MAX_SONGS,
                )
            return RecommendationEngine.dailyMix(familiar, fresh, size = PlaylistBuilder.MAX_SONGS)
        }

        private suspend fun loadSongs(
            ids: List<String>,
            filters: RecommendationFilters,
        ): List<Song> =
            ids
                .chunked(SQL_CHUNK)
                .flatMap { database.getSongsByIds(it) }
                .filter { song ->
                    !song.song.isLocal &&
                        !song.song.isEpisode &&
                        !(filters.hideExplicit && song.song.explicit) &&
                        !(filters.hideVideoSongs && song.song.isVideo)
                }

        companion object {
            const val STYLE_SEEDS = 3
            const val CATEGORY_PLAYLISTS = 3
            const val PERSONAL_SEEDS = 5
            private const val STYLE_FAMILIAR_SHARE = 0.5
            private const val LOCAL_POOL = 300
            private const val SQL_CHUNK = 500
            private const val TITLE_MAX = 60
        }
    }
