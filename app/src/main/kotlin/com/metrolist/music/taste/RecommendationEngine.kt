/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import kotlin.math.ln
import kotlin.math.min

data class RecArtist(
    val id: String?,
    val name: String,
)

/** A real, playable song together with the object the UI plays (a YouTube Music [com.metrolist.innertube.models.SongItem]). */
data class RecSong<T>(
    val id: String,
    val title: String,
    val artists: List<RecArtist>,
    val payload: T,
)

/** Related songs YouTube Music returned for one seed song, in YouTube's own order. */
data class RelatedBatch<T>(
    val seedId: String,
    val seedTitle: String,
    val songs: List<RecSong<T>>,
)

/** Why a song is recommended. Every reason is taken from the data that produced the pick. */
sealed interface RecommendationReason {
    data object Liked : RecommendationReason

    data class OnRepeat(
        val listensThisMonth: Int,
    ) : RecommendationReason

    data class FavoriteArtist(
        val artistName: String,
    ) : RecommendationReason

    data class SimilarTo(
        val songTitle: String,
        val seedLiked: Boolean,
    ) : RecommendationReason

    data class SameArtist(
        val artistName: String,
    ) : RecommendationReason

    data object BasedOnListening : RecommendationReason

    /** Picked for a smart playlist that asked for songs in the style of this artist. */
    data class StyleOf(
        val artistName: String,
    ) : RecommendationReason

    /** Taken from YouTube Music playlists of this mood or genre. */
    data class FromCategory(
        val category: String,
    ) : RecommendationReason

    /** A song the user enjoyed but has not played for this many days. */
    data class NotPlayedFor(
        val days: Int,
    ) : RecommendationReason
}

data class Recommendation<T>(
    val song: RecSong<T>,
    val score: Double,
    val reason: RecommendationReason,
    /** True when the user never heard the song before. */
    val isNew: Boolean,
    /** The seed song whose related list produced this pick, when there is one. */
    val seedId: String? = null,
)

/**
 * Local recommendation rules on top of the [TasteProfile]. Candidate songs only come from the
 * user's own history or from YouTube Music related lists, so every result is a real song.
 * Pure and deterministic for a given day, so it is covered by unit tests.
 */
object RecommendationEngine {
    const val ON_REPEAT_MIN_LISTENS = 3
    const val FAVORITE_ARTIST_REASON_MIN_SCORE = 2.0
    private const val ARTIST_BOOST_CAP = 6.0
    private const val ARTIST_BOOST = 1.5
    const val BLOCKED_ARTIST_SCORE = -2.0
    private const val EXTRA_SEED_BONUS = 0.5
    private const val SEED_WEIGHT = 0.3
    private const val SKIPPED_SONG_MIN_SKIPS = 2
    private const val SKIPPED_SONG_MIN_RATE = 0.5

    /** Same value for the same song on the same day, different across days. 0 until 1. */
    fun dayRandom(
        dayKey: Long,
        id: String,
    ): Double {
        var h = dayKey * 0x9E3779B97F4A7C15uL.toLong() xor id.hashCode().toLong()
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    private class ArtistLookup(
        profile: TasteProfile,
    ) {
        private val byId = profile.artists.associateBy { it.artistId }
        private val byName = profile.artists.groupBy { it.name.lowercase() }.mapValues { (_, v) -> v.maxBy { it.score } }

        fun find(artist: RecArtist): ArtistTaste? = artist.id?.let { byId[it] } ?: byName[artist.name.lowercase()]

        /** Best known artist on the song (for the boost), plus the worst (to block disliked artists). */
        fun best(song: RecSong<*>): ArtistTaste? = song.artists.mapNotNull { find(it) }.maxByOrNull { it.score }

        fun worst(song: RecSong<*>): ArtistTaste? = song.artists.mapNotNull { find(it) }.minByOrNull { it.score }
    }

    private fun artistBoost(artist: ArtistTaste?): Double {
        val score = artist?.score ?: return 0.0
        return if (score > 0) min(score, ARTIST_BOOST_CAP) / ARTIST_BOOST_CAP * ARTIST_BOOST else score
    }

    private fun isAvoided(taste: SongTaste?): Boolean =
        taste != null &&
            (
                taste.disliked ||
                    (taste.skips >= SKIPPED_SONG_MIN_SKIPS && taste.skipRate >= SKIPPED_SONG_MIN_RATE)
            )

    private fun mainArtistKey(song: RecSong<*>): String =
        song.artists.firstOrNull()?.let { it.id ?: it.name.lowercase() } ?: song.id

    /** Keeps the order but allows at most [perArtist] songs from the same main artist. */
    fun <T> diversify(
        ranked: List<Recommendation<T>>,
        perArtist: Int,
    ): List<Recommendation<T>> {
        val counts = HashMap<String, Int>()
        return ranked.filter { rec ->
            val key = mainArtistKey(rec.song)
            val n = counts.getOrDefault(key, 0)
            if (n >= perArtist) {
                false
            } else {
                counts[key] = n + 1
                true
            }
        }
    }

    fun familiarReason(
        taste: SongTaste,
        song: RecSong<*>,
        profile: TasteProfile,
    ): RecommendationReason {
        if (taste.liked) return RecommendationReason.Liked
        if (taste.recentListens >= ON_REPEAT_MIN_LISTENS) return RecommendationReason.OnRepeat(taste.recentListens)
        val artist = ArtistLookup(profile).best(song)
        if (artist != null && artist.score >= FAVORITE_ARTIST_REASON_MIN_SCORE && profile.favoriteArtists.any { it.artistId == artist.artistId }) {
            return RecommendationReason.FavoriteArtist(artist.name)
        }
        return RecommendationReason.BasedOnListening
    }

    /**
     * Songs the user already enjoys, ranked by taste score with a small daily shuffle, without
     * songs heard in the last hours.
     */
    fun <T> quickPicks(
        profile: TasteProfile,
        songs: Map<String, RecSong<T>>,
        recentlyPlayed: Set<String>,
        dayKey: Long,
        limit: Int = 20,
        perArtist: Int = 3,
    ): List<Recommendation<T>> {
        val ranked =
            profile.songs
                .asSequence()
                .filter { it.score > 0 && !isAvoided(it) && it.songId !in recentlyPlayed }
                .mapNotNull { taste ->
                    val song = songs[taste.songId] ?: return@mapNotNull null
                    Recommendation(
                        song = song,
                        score = taste.score * (0.85 + 0.3 * dayRandom(dayKey, taste.songId)),
                        reason = familiarReason(taste, song, profile),
                        isNew = false,
                    )
                }.sortedWith(compareByDescending<Recommendation<T>> { it.score }.thenBy { it.song.id })
                .toList()
        return diversify(ranked, perArtist).take(limit)
    }

    /** Seed songs for discovery: the strongest favorites, rotated daily. */
    fun seeds(
        profile: TasteProfile,
        dayKey: Long,
        count: Int = 5,
        pool: Int = 15,
    ): List<SongTaste> {
        val base =
            profile.favoriteSongs.ifEmpty { profile.songs.filter { it.score > 0 && !it.disliked } }
        return base
            .take(pool)
            .sortedBy { dayRandom(dayKey, it.songId) }
            .take(count)
    }

    /**
     * Songs the user has never heard, taken from YouTube Music's related lists for the seeds and
     * re-ranked by the user's artist preferences. Songs recommended by several seeds rise.
     */
    fun <T> discover(
        profile: TasteProfile,
        batches: List<RelatedBatch<T>>,
        exclude: Set<String> = emptySet(),
        limit: Int = 20,
        perArtist: Int = 2,
    ): List<Recommendation<T>> {
        val artists = ArtistLookup(profile)
        val merged = LinkedHashMap<String, Recommendation<T>>()
        for (batch in batches) {
            val seed = profile.song(batch.seedId)
            val seedBonus = SEED_WEIGHT * ln(1.0 + (seed?.score ?: 0.0).coerceAtLeast(0.0))
            val size = batch.songs.size.coerceAtLeast(1)
            batch.songs.forEachIndexed { index, song ->
                if (song.id == batch.seedId || song.id in exclude) return@forEachIndexed
                val known = profile.song(song.id)
                if (known != null) return@forEachIndexed
                val worst = artists.worst(song)
                if (worst != null && worst.score <= BLOCKED_ARTIST_SCORE) return@forEachIndexed

                val best = artists.best(song)
                val score = (1.0 - index.toDouble() / size) + seedBonus + artistBoost(best)
                val reason =
                    if (best != null && best.score >= FAVORITE_ARTIST_REASON_MIN_SCORE) {
                        RecommendationReason.FavoriteArtist(best.name)
                    } else {
                        RecommendationReason.SimilarTo(batch.seedTitle, seed?.liked == true)
                    }
                val previous = merged[song.id]
                merged[song.id] =
                    if (previous == null) {
                        Recommendation(song, score, reason, isNew = true, seedId = batch.seedId)
                    } else {
                        previous.copy(score = maxOf(previous.score, score) + EXTRA_SEED_BONUS)
                    }
            }
        }
        val ranked = merged.values.sortedWith(compareByDescending<Recommendation<T>> { it.score }.thenBy { it.song.id })
        return diversify(ranked, perArtist).take(limit)
    }

    /**
     * Two familiar songs, then one new song, repeated; no song twice and, where possible, never the
     * same main artist twice in a row.
     */
    fun <T> dailyMix(
        familiar: List<Recommendation<T>>,
        fresh: List<Recommendation<T>>,
        size: Int = 30,
        familiarPerNew: Int = 2,
    ): List<Recommendation<T>> {
        val familiarLeft = familiar.toMutableList()
        val freshLeft = fresh.toMutableList()
        val used = HashSet<String>()
        val result = ArrayList<Recommendation<T>>(size)
        var familiarInRow = 0

        fun take(from: MutableList<Recommendation<T>>): Recommendation<T>? {
            from.removeAll { it.song.id in used }
            if (from.isEmpty()) return null
            val previousArtist = result.lastOrNull()?.let { mainArtistKey(it.song) }
            val index = from.indexOfFirst { mainArtistKey(it.song) != previousArtist }.takeIf { it >= 0 } ?: 0
            return from.removeAt(index)
        }

        while (result.size < size && (familiarLeft.isNotEmpty() || freshLeft.isNotEmpty())) {
            val wantFresh = familiarInRow >= familiarPerNew
            val next =
                if (wantFresh) take(freshLeft) ?: take(familiarLeft) else take(familiarLeft) ?: take(freshLeft)
            next ?: break
            used += next.song.id
            result += next
            familiarInRow = if (next.isNew) 0 else familiarInRow + 1
        }
        return result
    }

    /**
     * "More like this" for one song: YouTube Music's related list for it, re-ranked by the profile.
     * Avoided songs and disliked artists are dropped, and at most [maxFamiliarShare] of the result
     * are songs the user already knows, so it keeps discovering.
     */
    fun <T> moreLikeThis(
        seed: RecSong<*>,
        related: List<RecSong<T>>,
        profile: TasteProfile,
        limit: Int = 40,
        perArtist: Int = 3,
        maxFamiliarShare: Double = 0.4,
    ): List<Recommendation<T>> {
        val artists = ArtistLookup(profile)
        val seedArtist = seed.artists.firstOrNull()
        val seedLiked = profile.song(seed.id)?.liked == true
        val size = related.size.coerceAtLeast(1)
        val candidates =
            related
                .asSequence()
                .distinctBy { it.id }
                .filter { it.id != seed.id }
                .mapIndexedNotNull { index, song ->
                    val taste = profile.song(song.id)
                    if (isAvoided(taste)) return@mapIndexedNotNull null
                    val worst = artists.worst(song)
                    if (worst != null && worst.score <= BLOCKED_ARTIST_SCORE) return@mapIndexedNotNull null
                    val best = artists.best(song)
                    val songAffinity = (taste?.score ?: 0.0).coerceIn(-2.0, 5.0) * 0.15
                    val score = (1.0 - index.toDouble() / size) + artistBoost(best) + songAffinity
                    val main = song.artists.firstOrNull()
                    val reason =
                        when {
                            seedArtist != null && main != null && sameArtist(seedArtist, main) ->
                                RecommendationReason.SameArtist(main.name)
                            taste?.liked == true -> RecommendationReason.Liked
                            best != null && best.score >= FAVORITE_ARTIST_REASON_MIN_SCORE ->
                                RecommendationReason.FavoriteArtist(best.name)
                            else -> RecommendationReason.SimilarTo(seed.title, seedLiked)
                        }
                    Recommendation(song, score, reason, isNew = taste == null || taste.listens == 0)
                }.sortedWith(compareByDescending<Recommendation<T>> { it.score }.thenBy { it.song.id })
                .toList()

        val maxFamiliar = (limit * maxFamiliarShare).toInt()
        var familiar = 0
        val capped =
            candidates.filter { rec ->
                if (rec.isNew) {
                    true
                } else if (familiar < maxFamiliar) {
                    familiar++
                    true
                } else {
                    false
                }
            }
        return diversify(capped, perArtist).take(limit)
    }

    /**
     * SmartQueue: refines songs that autoplay is about to add. Removes songs already in the queue,
     * duplicates, disliked or repeatedly skipped songs and disliked artists, then re-ranks the rest by
     * taste while keeping YouTube Music's order as the main signal. Familiar and new songs both stay.
     */
    fun <T> refineQueue(
        page: List<RecSong<T>>,
        profile: TasteProfile,
        alreadyQueued: Set<String>,
    ): List<RecSong<T>> {
        if (page.isEmpty()) return page
        val artists = ArtistLookup(profile)
        val size = page.size
        return page
            .asSequence()
            .withIndex()
            .filter { (_, song) -> song.id !in alreadyQueued }
            .distinctBy { (_, song) -> song.id }
            .filter { (_, song) ->
                val worst = artists.worst(song)
                !isAvoided(profile.song(song.id)) && !(worst != null && worst.score <= BLOCKED_ARTIST_SCORE)
            }.map { (index, song) ->
                val songAffinity = (profile.song(song.id)?.score ?: 0.0).coerceIn(-2.0, 5.0) * 0.1
                song to (1.0 - index.toDouble() / size) * 2.0 + artistBoost(artists.best(song)) * 0.5 + songAffinity
            }.sortedByDescending { it.second }
            .map { it.first }
            .toList()
    }

    /**
     * One page of the personal radio: related songs of several seeds, interleaved, then ranked like
     * "more like this" without anything already played or queued.
     */
    fun <T> radioPage(
        seed: RecSong<*>,
        batches: List<List<RecSong<T>>>,
        profile: TasteProfile,
        exclude: Set<String>,
        limit: Int = 15,
        maxFamiliarShare: Double = 0.35,
    ): List<Recommendation<T>> {
        val interleaved = ArrayList<RecSong<T>>()
        val longest = batches.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (batch in batches) batch.getOrNull(i)?.let { if (it.id !in exclude) interleaved += it }
        }
        return moreLikeThis(seed, interleaved, profile, limit = limit, maxFamiliarShare = maxFamiliarShare)
    }

    private fun sameArtist(
        a: RecArtist,
        b: RecArtist,
    ): Boolean = if (a.id != null && b.id != null) a.id == b.id else a.name.equals(b.name, ignoreCase = true)
}
