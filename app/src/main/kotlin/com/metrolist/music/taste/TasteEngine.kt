/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import java.time.LocalDateTime
import kotlin.math.ln
import kotlin.math.min

data class SongTaste(
    val songId: String,
    val score: Double,
    val listens: Int,
    /** Meaningful listens in the last [TasteEngine.RECENT_DAYS] days. */
    val recentListens: Int,
    /** Meaningful listening time plus the time spent in songs before skipping them. */
    val listeningTimeMs: Long,
    /** Share of the song actually heard per play, 0..1; null when the length is unknown. */
    val completionRate: Double?,
    val skips: Int,
    /** Skips out of all plays (listens + skips). */
    val skipRate: Double,
    val liked: Boolean,
    val disliked: Boolean,
    val moreLikeThis: Int,
    /** Time of the latest meaningful listen, null when never heard. */
    val lastPlayed: LocalDateTime? = null,
)

data class ArtistTaste(
    val artistId: String,
    val name: String,
    val score: Double,
    val listens: Int,
    val listeningTimeMs: Long,
    val songCount: Int,
    val likedSongs: Int,
    val dislikedSongs: Int,
    val skips: Int,
    val completionRate: Double?,
)

data class TasteProfile(
    /** All songs with any signal, best first. */
    val songs: List<SongTaste>,
    /** All artists with any signal, best first. */
    val artists: List<ArtistTaste>,
    val totalListeningTimeMs: Long,
    val totalListens: Int,
    val totalSkips: Int,
    val completionRate: Double?,
    val favoriteSongs: List<SongTaste>,
    val favoriteArtists: List<ArtistTaste>,
) {
    private val songIndex by lazy { songs.associateBy { it.songId } }
    private val artistIndex by lazy { artists.associateBy { it.artistId } }

    fun song(songId: String): SongTaste? = songIndex[songId]

    fun artist(artistId: String): ArtistTaste? = artistIndex[artistId]

    val isEmpty: Boolean get() = songs.isEmpty()

    companion object {
        val EMPTY = TasteProfile(emptyList(), emptyList(), 0, 0, 0, null, emptyList(), emptyList())
    }
}

/**
 * Turns aggregated listening data into a taste profile. Pure and deterministic, so the scoring
 * is fully covered by unit tests. Recency is applied in SQL through the listen buckets.
 *
 * Song score:
 *   heard      = log(1 + recent*1.0 + mid*0.6 + older*0.3)        diminishing returns on repeats
 *   completion = 0.5 + completionRate (0.5..1.5, 1.0 when unknown)
 *   score      = heard * completion
 *              + 2.0 if liked
 *              + 0.75 per "more like this" (max 3)
 *              - 2.0 * skipRate * min(1, skips / 3)                 one accidental skip barely counts
 *   disliked songs are fixed at [DISLIKED_SONG_SCORE].
 *
 * Artist score: sum over the artist's songs of song score, weighted 1.0 for the main artist and
 * 0.5 for featured artists; a disliked song counts as [DISLIKED_SONG_ARTIST_PENALTY].
 */
object TasteEngine {
    const val RECENT_DAYS = 30L
    const val MID_DAYS = 180L

    private const val RECENT_WEIGHT = 1.0
    private const val MID_WEIGHT = 0.6
    private const val OLD_WEIGHT = 0.3
    private const val LIKE_BONUS = 2.0
    private const val MORE_LIKE_THIS_BONUS = 0.75
    private const val MORE_LIKE_THIS_MAX = 3
    private const val SKIP_PENALTY = 2.0
    private const val SKIPS_FOR_FULL_PENALTY = 3.0
    private const val FEATURED_ARTIST_WEIGHT = 0.5

    const val DISLIKED_SONG_SCORE = -5.0
    const val DISLIKED_SONG_ARTIST_PENALTY = -1.5

    const val FAVORITE_SONG_MIN_SCORE = 1.5
    const val FAVORITE_ARTIST_MIN_LISTENS = 3
    const val FAVORITE_SONGS_LIMIT = 50
    const val FAVORITE_ARTISTS_LIMIT = 20

    fun completionRate(signals: SongSignals): Double? {
        val timedPlays = signals.timedListens + signals.timedSkips
        if (timedPlays == 0) return null
        return ((signals.completionSum + signals.skipCompletionSum) / timedPlays).coerceIn(0.0, 1.0)
    }

    fun skipRate(signals: SongSignals): Double {
        val plays = signals.listens + signals.skips
        return if (plays == 0) 0.0 else signals.skips.toDouble() / plays
    }

    fun songScore(
        signals: SongSignals,
        disliked: Boolean,
    ): Double {
        if (disliked) return DISLIKED_SONG_SCORE
        val olderListens = (signals.listens - signals.recentListens - signals.midListens).coerceAtLeast(0)
        val weightedListens =
            signals.recentListens * RECENT_WEIGHT + signals.midListens * MID_WEIGHT + olderListens * OLD_WEIGHT
        val heard = ln(1.0 + weightedListens)
        val completion = 0.5 + (completionRate(signals) ?: 0.5)
        val likeBonus = if (signals.liked) LIKE_BONUS else 0.0
        val moreBonus = min(signals.moreLikeThis, MORE_LIKE_THIS_MAX) * MORE_LIKE_THIS_BONUS
        val skipPenalty = SKIP_PENALTY * skipRate(signals) * min(1.0, signals.skips / SKIPS_FOR_FULL_PENALTY)
        return heard * completion + likeBonus + moreBonus - skipPenalty
    }

    fun build(
        signals: List<SongSignals>,
        artistLinks: List<SongArtistLink>,
        dislikedSongIds: Set<String>,
    ): TasteProfile {
        if (signals.isEmpty()) return TasteProfile.EMPTY

        val songs =
            signals
                .map { s ->
                    SongTaste(
                        songId = s.songId,
                        score = songScore(s, s.songId in dislikedSongIds),
                        listens = s.listens,
                        recentListens = s.recentListens,
                        listeningTimeMs = s.playMs + s.skipPositionMs,
                        completionRate = completionRate(s),
                        skips = s.skips,
                        skipRate = skipRate(s),
                        liked = s.liked,
                        disliked = s.songId in dislikedSongIds,
                        moreLikeThis = s.moreLikeThis,
                        lastPlayed = s.lastPlayed,
                    )
                }.sortedWith(compareByDescending<SongTaste> { it.score }.thenBy { it.songId })

        val songById = songs.associateBy { it.songId }
        val signalsById = signals.associateBy { it.songId }

        val artists =
            artistLinks
                .filter { it.songId in songById }
                .distinctBy { it.songId to it.artistId }
                .groupBy { it.artistId }
                .map { (artistId, links) ->
                    var score = 0.0
                    var completionSum = 0.0
                    var timedPlays = 0
                    for (link in links) {
                        val song = songById.getValue(link.songId)
                        val weight = if (link.position == 0) 1.0 else FEATURED_ARTIST_WEIGHT
                        score += weight * if (song.disliked) DISLIKED_SONG_ARTIST_PENALTY else song.score
                        val s = signalsById.getValue(link.songId)
                        completionSum += s.completionSum + s.skipCompletionSum
                        timedPlays += s.timedListens + s.timedSkips
                    }
                    val artistSongs = links.map { songById.getValue(it.songId) }
                    ArtistTaste(
                        artistId = artistId,
                        name = links.first().artistName,
                        score = score,
                        listens = artistSongs.sumOf { it.listens },
                        listeningTimeMs = artistSongs.sumOf { it.listeningTimeMs },
                        songCount = artistSongs.size,
                        likedSongs = artistSongs.count { it.liked && !it.disliked },
                        dislikedSongs = artistSongs.count { it.disliked },
                        skips = artistSongs.sumOf { it.skips },
                        completionRate = if (timedPlays == 0) null else (completionSum / timedPlays).coerceIn(0.0, 1.0),
                    )
                }.sortedWith(compareByDescending<ArtistTaste> { it.score }.thenBy { it.artistId })

        val timedPlays = signals.sumOf { it.timedListens + it.timedSkips }
        return TasteProfile(
            songs = songs,
            artists = artists,
            totalListeningTimeMs = songs.sumOf { it.listeningTimeMs },
            totalListens = songs.sumOf { it.listens },
            totalSkips = songs.sumOf { it.skips },
            completionRate =
                if (timedPlays == 0) {
                    null
                } else {
                    (signals.sumOf { it.completionSum + it.skipCompletionSum } / timedPlays).coerceIn(0.0, 1.0)
                },
            favoriteSongs =
                songs
                    .filter { !it.disliked && it.score >= FAVORITE_SONG_MIN_SCORE }
                    .take(FAVORITE_SONGS_LIMIT),
            favoriteArtists =
                artists
                    .filter { it.score > 0 && it.listens >= FAVORITE_ARTIST_MIN_LISTENS }
                    .take(FAVORITE_ARTISTS_LIMIT),
        )
    }
}
