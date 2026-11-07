/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.importer

import com.metrolist.innertube.models.SongItem
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Why a Spotify track has no song in the import. Shown to the user next to the track. */
enum class NotFoundReason {
    /** The search returned no songs. */
    NoResults,

    /** Songs came back, but none was close enough in title, artist and length. */
    NoConfidentMatch,

    /** The only good match is explicit and explicit songs are hidden in the settings. */
    OnlyExplicit,

    /** The only good match is a music video and video songs are hidden in the settings. */
    OnlyVideo,

    /** YouTube Music could not be reached. */
    SearchFailed,
}

sealed interface MatchResult {
    data class Matched(
        val song: SongItem,
        val confidence: Double,
    ) : MatchResult

    data class NotFound(
        val reason: NotFoundReason,
    ) : MatchResult
}

data class MatchFilters(
    val hideExplicit: Boolean = false,
    val hideVideoSongs: Boolean = false,
)

/**
 * Picks the YouTube Music song that is the same recording as a Spotify track, or none.
 * Never guesses: a candidate must agree on the title and the artist (or, across scripts, on the
 * title and an almost identical length), and must not be far off in length.
 */
object TrackMatcher {
    const val THRESHOLD = 0.75
    private const val MIN_TITLE_SIMILARITY = 0.7

    /** The search text for a track: the title without edition notes, then the main artist. */
    fun query(track: SpotifyTrack): String {
        val title = cleanTitleForQuery(track.title)
        return listOfNotNull(title, track.mainArtist).joinToString(" ")
    }

    private fun cleanTitleForQuery(title: String): String {
        val withoutBrackets =
            title.replace(Regex("\\s*[(\\[][^)\\]]*\\b(?i:feat|ft|featuring|with|remaster|remastered)\\b[^)\\]]*[)\\]]"), "")
        val dash = Regex("\\s[-–—]\\s.*\\b(?i:remaster|remastered|feat|ft)\\b.*$")
        return withoutBrackets.replace(dash, "").trim().ifEmpty { title }
    }

    fun match(
        track: SpotifyTrack,
        candidates: List<SongItem>,
        filters: MatchFilters = MatchFilters(),
    ): MatchResult {
        if (candidates.isEmpty()) return MatchResult.NotFound(NotFoundReason.NoResults)
        val scored =
            candidates
                .distinctBy { it.id }
                .filter { !it.isEpisode }
                .map { it to score(track, it) }
                .filter { it.second >= THRESHOLD }
        if (scored.isEmpty()) return MatchResult.NotFound(NotFoundReason.NoConfidentMatch)

        // Choose among good matches. Ranking prefers audio songs; the settings exclude.
        val allowed =
            scored.filter { (song, _) ->
                !(filters.hideExplicit && song.explicit) && !(filters.hideVideoSongs && song.isVideoSong)
            }
        if (allowed.isEmpty()) {
            val explicitBlocked = filters.hideExplicit && scored.all { it.first.explicit }
            return MatchResult.NotFound(if (explicitBlocked) NotFoundReason.OnlyExplicit else NotFoundReason.OnlyVideo)
        }
        val best = allowed.maxWith(compareBy<Pair<SongItem, Double>> { it.second }.thenBy { !it.first.isVideoSong })
        return MatchResult.Matched(best.first, min(1.0, best.second))
    }

    /** Score of one candidate; [THRESHOLD] or more means the same recording. */
    fun score(
        track: SpotifyTrack,
        candidate: SongItem,
    ): Double {
        val titleSimilarity = titleSimilarity(track.title, candidate.title)
        if (titleSimilarity < MIN_TITLE_SIMILARITY) return 0.0

        val candidateSeconds = candidate.duration?.takeIf { it > 0 }
        val durationDiff =
            if (track.durationMs != null && candidateSeconds != null) {
                abs(track.durationMs / 1000.0 - candidateSeconds)
            } else {
                null
            }

        var artist = artistScore(track.artists, candidate.artists.map { it.name })
        if (artist == 0.0 && crossScript(track.artists, candidate.artists.map { it.name }) &&
            titleSimilarity >= 0.9 && durationDiff != null && durationDiff <= 3.0
        ) {
            // "Omer Adam" on Spotify, "עומר אדם" on YouTube Music: names can't be compared,
            // so only the same title with almost the same length counts.
            artist = 0.6
        }

        var score = 0.55 * titleSimilarity + 0.35 * artist
        score +=
            when {
                durationDiff == null -> 0.0
                durationDiff <= 7.0 -> 0.15
                durationDiff <= 15.0 -> 0.0
                durationDiff <= 30.0 -> -0.1
                else -> -0.35
            }

        val wanted = TrackText.variants(track.title) + TrackText.variants(track.album.orEmpty()).filter { it == "live" }
        val offered = TrackText.variants(candidate.title)
        val extra = offered - wanted
        val missing = wanted - offered
        if (extra.any { it in TrackText.unwantedWords }) score -= 0.5
        if (extra.isNotEmpty() || missing.isNotEmpty()) score -= 0.2
        if (candidate.isVideoSong) score -= 0.05
        return max(0.0, score)
    }

    fun titleSimilarity(
        a: String,
        b: String,
    ): Double {
        // Core titles ("Song" for "Song (feat. X)") and full titles, for names such as
        // "Sunflower - Spider-Man: Into the Spider-Verse" whose suffix is part of the name.
        return max(
            similarity(TrackText.coreTitle(a), TrackText.coreTitle(b)),
            similarity(TrackText.normalize(a), TrackText.normalize(b)),
        )
    }

    private fun similarity(
        a: String,
        b: String,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        return max(levenshteinRatio(a, b), tokenDice(a, b))
    }

    /**
     * 1.0 when the main artist is among the candidate's artists, 0.8 when another credited
     * artist is, otherwise 0.0.
     */
    fun artistScore(
        spotify: List<String>,
        youtube: List<String>,
    ): Double {
        if (spotify.isEmpty() || youtube.isEmpty()) return 0.0
        val ours = spotify.map(TrackText::normalize).filter { it.isNotEmpty() }
        // YouTube Music sometimes credits "A & B" or "A, B" as one artist.
        val theirs =
            youtube
                .flatMap { name ->
                    listOf(TrackText.normalize(name)) +
                        name
                            .split(Regex("\\s*(?:,|&| and | x | feat\\. | ft\\. )\\s*"))
                            .map(TrackText::normalize)
                            .filter { it.length >= 4 }
                }.filter { it.isNotEmpty() }
        if (ours.isEmpty() || theirs.isEmpty()) return 0.0
        fun same(x: String) = theirs.any { y -> x == y || containsWords(x, y) || containsWords(y, x) || levenshteinRatio(x, y) >= 0.85 }
        return when {
            same(ours.first()) -> 1.0
            ours.drop(1).any(::same) -> 0.8
            else -> 0.0
        }
    }

    private fun crossScript(
        spotify: List<String>,
        youtube: List<String>,
    ): Boolean {
        val a = spotify.joinToString(" ")
        val b = youtube.joinToString(" ")
        val aLatinOnly = TrackText.hasLatinLetters(a) && !TrackText.hasNonLatinLetters(a)
        val bLatinOnly = TrackText.hasLatinLetters(b) && !TrackText.hasNonLatinLetters(b)
        val aOther = TrackText.hasNonLatinLetters(a) && !TrackText.hasLatinLetters(a)
        val bOther = TrackText.hasNonLatinLetters(b) && !TrackText.hasLatinLetters(b)
        return (aLatinOnly && bOther) || (aOther && bLatinOnly)
    }

    /** True when [inner] (4+ characters) appears in [outer] as whole words: "tyler" in "tyler the creator". */
    private fun containsWords(
        outer: String,
        inner: String,
    ): Boolean {
        if (inner.length < 4) return false
        return " $outer ".contains(" $inner ")
    }

    private fun tokenDice(
        a: String,
        b: String,
    ): Double {
        val ta = a.split(' ').filter { it.isNotEmpty() }
        val tb = b.split(' ').filter { it.isNotEmpty() }
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val remaining = tb.toMutableList()
        var common = 0
        for (t in ta) if (remaining.remove(t)) common++
        return 2.0 * common / (ta.size + tb.size)
    }

    fun levenshteinRatio(
        a: String,
        b: String,
    ): Double {
        if (a == b) return 1.0
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return 1.0 - previous[b.length].toDouble() / longest
    }
}
