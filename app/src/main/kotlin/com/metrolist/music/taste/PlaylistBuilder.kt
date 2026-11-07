/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import java.time.Duration
import java.time.LocalDateTime

data class FilledPlaylist<T>(
    val songs: List<Recommendation<T>>,
    val totalMs: Long,
)

/** Pure helpers that turn ranked real songs into a playlist of the requested length. */
object PlaylistBuilder {
    const val DEFAULT_SONG_COUNT = 25
    const val MAX_SONGS = 200
    const val MAX_PER_ARTIST = 3
    const val OVERSHOOT_MS = 2 * 60_000L
    const val TOLERANCE_MS = 90_000L
    const val FORGOTTEN_DAYS = 60L

    /**
     * The mood or genre title that best matches [words], or null. Case-insensitive and stem-based
     * (see [PlaylistRequestParser.stem]); a title written in full inside the request wins.
     */
    fun matchCategory(
        words: List<String>,
        categories: List<String>,
    ): String? {
        if (words.isEmpty()) return null
        val wordStems = words.map { PlaylistRequestParser.stem(it) }.filter { it.isNotEmpty() }.toSet()
        val joined = " " + words.joinToString(" ") { it.lowercase() }.split(Regex("[^\\p{L}\\p{N}]+")).joinToString(" ") + " "
        var best: String? = null
        var bestScore = 0.0
        for (category in categories) {
            val titleWords = category.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
            if (titleWords.isEmpty()) continue
            val stems =
                PlaylistRequestParser
                    .words(category)
                    .map { PlaylistRequestParser.stem(it) }
                    .filter { it.isNotEmpty() }
                    .distinct()
            val score =
                if (joined.contains(" " + titleWords.joinToString(" ") + " ")) {
                    3.0 + titleWords.size * 0.01
                } else if (stems.isEmpty()) {
                    0.0
                } else {
                    val hits = stems.count { it in wordStems }
                    if (hits == 0) 0.0 else hits.toDouble() / stems.size + hits * 0.01
                }
            if (score > bestScore) {
                best = category
                bestScore = score
            }
        }
        return best
    }

    /** True when every word of [name] matches the category title exactly (after stemming). */
    fun isWholeCategory(
        name: String,
        category: String,
    ): Boolean {
        val a = PlaylistRequestParser.words(name).map { PlaylistRequestParser.stem(it) }.toSet()
        val b = PlaylistRequestParser.words(category).map { PlaylistRequestParser.stem(it) }.toSet()
        return a.isNotEmpty() && a == b
    }

    private fun mainArtistKey(song: RecSong<*>): String =
        song.artists.firstOrNull()?.let { it.id ?: it.name.lowercase() } ?: song.id

    /**
     * Walks [ranked] in order and picks songs until the target is reached.
     *
     * With a duration target, songs of unknown length are skipped, a song is skipped when it would
     * overshoot the target by more than [OVERSHOOT_MS], and filling stops once the total is within
     * [TOLERANCE_MS] of the target. With a count target it stops at that count. Without any target
     * it takes [DEFAULT_SONG_COUNT] songs. No duplicates and at most [perArtist] songs per main
     * artist (null = no limit, for "only this artist" playlists).
     */
    fun <T> fillToDuration(
        ranked: List<Recommendation<T>>,
        durationMs: (T) -> Long?,
        targetMs: Long?,
        targetCount: Int?,
        perArtist: Int? = MAX_PER_ARTIST,
    ): FilledPlaylist<T> {
        val countLimit = (targetCount ?: if (targetMs == null) DEFAULT_SONG_COUNT else MAX_SONGS).coerceAtMost(MAX_SONGS)
        val used = HashSet<String>()
        val perArtistCount = HashMap<String, Int>()
        val chosen = ArrayList<Recommendation<T>>()
        var total = 0L
        for (rec in ranked) {
            if (chosen.size >= countLimit) break
            if (targetMs != null && total >= targetMs - TOLERANCE_MS) break
            if (rec.song.id in used) continue
            val key = mainArtistKey(rec.song)
            if (perArtist != null && perArtistCount.getOrDefault(key, 0) >= perArtist) continue
            val length = durationMs(rec.song.payload)?.takeIf { it > 0 }
            if (targetMs != null) {
                if (length == null) continue
                if (total + length > targetMs + OVERSHOOT_MS) continue
            }
            used += rec.song.id
            perArtistCount[key] = perArtistCount.getOrDefault(key, 0) + 1
            chosen += rec
            total += length ?: 0L
        }
        return FilledPlaylist(chosen, total)
    }

    /** Songs the user disliked, and songs by artists the user dislikes, never appear. */
    fun <T> withoutDisliked(
        recs: List<Recommendation<T>>,
        profile: TasteProfile,
    ): List<Recommendation<T>> {
        val byId = profile.artists.associateBy { it.artistId }
        val byName = profile.artists.groupBy { it.name.lowercase() }
        return recs.filter { rec ->
            if (profile.song(rec.song.id)?.disliked == true) return@filter false
            rec.song.artists.none { artist ->
                val known = artist.id?.let { byId[it] }?.let { listOf(it) } ?: byName[artist.name.lowercase()].orEmpty()
                known.any { it.score <= RecommendationEngine.BLOCKED_ARTIST_SCORE }
            }
        }
    }

    /** Applies the familiarity, liked and forgotten flags of [request] using only local history. */
    fun <T> applyFlags(
        recs: List<Recommendation<T>>,
        request: PlaylistRequest,
        profile: TasteProfile,
        now: LocalDateTime,
    ): List<Recommendation<T>> =
        recs.filter { rec ->
            val taste = profile.song(rec.song.id)
            val heard = (taste?.listens ?: 0) > 0
            when {
                request.familiarity == Familiarity.NEW_ONLY && heard -> false
                request.familiarity == Familiarity.FAMILIAR_ONLY && !heard -> false
                request.likedOnly && taste?.liked != true -> false
                request.forgotten && (taste == null || !isForgotten(taste, now)) -> false
                else -> true
            }
        }

    /** Enjoyed (score above zero) and not played for [FORGOTTEN_DAYS] days. */
    fun isForgotten(
        taste: SongTaste,
        now: LocalDateTime,
    ): Boolean {
        val last = taste.lastPlayed ?: return false
        return taste.score > 0 && !taste.disliked && last.isBefore(now.minusDays(FORGOTTEN_DAYS))
    }

    fun daysSince(
        last: LocalDateTime,
        now: LocalDateTime,
    ): Int = Duration.between(last, now).toDays().toInt().coerceAtLeast(0)
}
