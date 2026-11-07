/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

enum class ArtistMode {
    /** Songs that sound like the artist: the artist's own songs plus YouTube Music related songs. */
    STYLE,

    /** Only songs by the artist. */
    ONLY,
}

enum class Familiarity {
    ANY,
    NEW_ONLY,
    FAMILIAR_ONLY,
}

/**
 * What the user asked for, read from free Hebrew or English text. Only what is actually written is
 * filled in; nothing is guessed.
 */
data class PlaylistRequest(
    val text: String,
    val durationMinutes: Double? = null,
    val songCount: Int? = null,
    val artist: String? = null,
    val artistMode: ArtistMode? = null,
    /**
     * The artist came from a plain possessive ("פלייליסט של X"), which can also name a genre
     * ("פלייליסט של רוק"); the repository then prefers an exact mood/genre match.
     */
    val artistAmbiguous: Boolean = false,
    val likedOnly: Boolean = false,
    val familiarity: Familiarity = Familiarity.ANY,
    val forgotten: Boolean = false,
    /** Remaining meaningful words, used to find a YouTube Music mood or genre. */
    val categoryWords: List<String> = emptyList(),
    /** An exact mood/genre title chosen from the list (a chip), bypassing word matching. */
    val category: String? = null,
) {
    val targetMs: Long? get() = durationMinutes?.let { (it * 60_000).toLong() }

    val usesLocalHistory: Boolean get() = likedOnly || forgotten || familiarity == Familiarity.FAMILIAR_ONLY

    val isRecognized: Boolean
        get() =
            durationMinutes != null ||
                songCount != null ||
                artist != null ||
                likedOnly ||
                forgotten ||
                familiarity != Familiarity.ANY ||
                categoryWords.isNotEmpty() ||
                category != null
}

/** Pure parser for playlist requests. No network, no AI: a fixed set of Hebrew and English phrases. */
object PlaylistRequestParser {
    private const val L = "(?<![\\p{L}\\p{N}])"
    private const val R = "(?![\\p{L}\\p{N}])"
    private val OPTIONS = setOf(RegexOption.IGNORE_CASE)

    private fun word(pattern: String) = Regex("$L(?:$pattern)$R", OPTIONS)

    private const val NUM = "(\\d+(?:\\.\\d+)?)"
    private const val HEB_PREFIX = "[לבשו]?"

    private val durationRules: List<Pair<Regex, (MatchResult) -> Double>> =
        listOf(
            Regex("(?<![\\p{N}.])$NUM\\s*-?\\s*(?:דקות|דקה|דק'|דק|minutes|minute|mins|min)$R", OPTIONS) to
                { m -> m.groupValues[1].toDouble() },
            Regex("(?<![\\p{N}.])$NUM\\s*-?\\s*(?:שעות|שעה|hours|hour|hrs|hr)$R", OPTIONS) to
                { m -> m.groupValues[1].toDouble() * 60 },
            word("${HEB_PREFIX}שעה וחצי|an hour and a half|one and a half hours?|hour and a half") to { _ -> 90.0 },
            word("${HEB_PREFIX}חצי שעה|half an hour|half-hour|half hour") to { _ -> 30.0 },
            word("${HEB_PREFIX}רבע שעה|quarter of an hour|quarter hour") to { _ -> 15.0 },
            word("${HEB_PREFIX}שעתיים|two hours") to { _ -> 120.0 },
            word("${HEB_PREFIX}שעה|an hour|one hour") to { _ -> 60.0 },
        )

    private val countRule =
        Regex("(?<![\\p{N}.])(\\d+)\\s*(?:שירים|שיר|songs|song|tracks|track)$R", OPTIONS)

    private val forgottenRule =
        word(
            "ו?ש?לא שמעתי (?:כבר )?(?:מזמן|הרבה זמן)|ו?ש?לא שמעתי מזה זמן|[הו]?מזמן|[הו]?נשכחים|[הו]?נשכחות|ו?ששכחתי|ו?שכחתי|[הו]?ישנים|[הו]?ישנות|" +
                "forgotten|(?:i )?haven'?t (?:heard|listened to)(?: in (?:a while|ages|a long time)| for a while)?",
        )
    private val newRule =
        word(
            "ו?ש?לא שמעתי(?: אף פעם| מעולם)?|ו?שלא הכרתי|[הו]?חדשים|[הו]?חדשות|[הו]?חדשה|[הו]?חדש|" +
                "never heard|brand new|new|discover|discovery",
        )
    private val familiarRule =
        word("ו?ש?אני מכיר(?:ה)?|[הו]?מוכרים|[הו]?מוכרות|[הו]?מוכר|familiar|known|i know")
    private val likedRule =
        word(
            "[והש]{0,2}אהבתי|ו?ש?אני אוהב(?:ת)?|[הו]?אהובים|[הו]?אהובות|" +
                "liked|favorites|favourites|favorite|favourite|i like|i love",
        )

    private val styleKeywords =
        Regex("$L(?:in the style of|in the style|similar to|sounds like|like|בסגנון של|בסגנון|בנוסח של|ברוח של|כמו)$R", OPTIONS)
    private val onlyKeywords = Regex("$L(?:only|just|by|רק|של|מאת)$R", OPTIONS)
    private val wouldLike = Regex("(?:^|\\s)(?:would|i'd|i)\\s*$", OPTIONS)

    private val separator =
        Regex(",|\\s(?:עם|בלי|with|without|וגם|and also)(?=\\s|$)|\\sו-|[.!?;:]", OPTIONS)

    /** Words that describe the request itself, never part of an artist name. */
    private val nameDrop =
        setOf(
            "פלייליסט", "playlist", "שירים", "songs", "שיר", "song", "מוזיקה", "מוסיקה", "music", "tracks", "track",
            "a", "an", "the", "some", "mix",
        )
    private val connectors = setOf("של", "by", "of", "from", "מאת", "for", "ל", "עם", "ו", "and", "to")

    private val stopwords =
        setOf(
            // Hebrew
            "פלייליסט", "פלייליסטים", "פליילסט", "רשימה", "רשימת", "השמעה", "תיצור", "תצור", "צור", "תיצרי", "תעשה", "תעשי",
            "עשה", "תכין", "תכיני", "הכן", "תבנה", "בנה", "תן", "תני", "תביא", "לי", "שלי", "אני", "רוצה", "של", "עם", "בלי",
            "את", "על", "אל", "עבור", "בשביל", "שירים", "שיר", "מוזיקה", "מוסיקה", "בבקשה", "משהו", "קצת", "גם", "רק", "זה",
            "יש", "כמה", "איזה", "הכי", "כל", "אותי", "לנו", "שאני", "יותר", "ממש", "אפשר", "שיהיה", "שיהיו", "דקות", "שעה",
            "מאת", "בסגנון", "כמו", "או", "בא", "תשמיע", "תנגן", "נגן", "שים", "תשים", "לשמוע",
            // English
            "please", "make", "me", "a", "an", "the", "playlist", "songs", "song", "music", "for", "some", "i", "want", "of",
            "with", "without", "create", "give", "build", "mix", "tracks", "track", "and", "to", "my", "in", "can", "you",
            "could", "would", "list", "generate", "get", "play", "something", "that", "are", "is", "just", "only", "by",
            "like", "minutes", "hour", "hours", "id", "d", "us", "from", "on", "it", "be", "or",
        )

    fun parse(text: String): PlaylistRequest {
        val original = normalize(text)
        var w = " $original "

        var duration: Double? = null
        for ((regex, value) in durationRules) {
            val m = regex.find(w) ?: continue
            if (duration == null) duration = value(m)
            w = w.replaceRange(m.range, " , ")
        }

        var count: Int? = null
        countRule.find(w)?.let { m ->
            count = m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
            w = w.replaceRange(m.range, " , ")
        }

        fun consume(rule: Regex): Boolean {
            var found = false
            while (true) {
                val m = rule.find(w) ?: break
                found = true
                w = w.replaceRange(m.range, " , ")
            }
            return found
        }

        val forgotten = consume(forgottenRule)
        val liked = consume(likedRule)
        val newOnly = consume(newRule)
        val familiar = consume(familiarRule)
        val familiarity =
            when {
                newOnly && !familiar -> Familiarity.NEW_ONLY
                familiar && !newOnly -> Familiarity.FAMILIAR_ONLY
                else -> Familiarity.ANY
            }

        var artist: String? = null
        var mode: ArtistMode? = null
        var ambiguous = false
        for ((keywords, kind) in listOf(styleKeywords to ArtistMode.STYLE, onlyKeywords to ArtistMode.ONLY)) {
            for (m in keywords.findAll(w).toList()) {
                val keyword = m.value.lowercase()
                if (keyword == "like" && wouldLike.containsMatchIn(w.substring(0, m.range.first))) continue
                val rest = w.substring(m.range.last + 1)
                val sep = separator.find(rest)?.range?.first ?: rest.length
                val name = cleanName(rest.substring(0, sep)) ?: continue
                artist = name
                mode = kind
                ambiguous = keyword == "של"
                w = w.replaceRange(m.range.first, m.range.last + 1 + sep, " , ")
                break
            }
            if (artist != null) break
        }

        val words =
            w
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotBlank() }
                .filter { token ->
                    val lower = token.lowercase()
                    lower !in stopwords &&
                        stem(lower) !in stopwords &&
                        (lower.length >= 2 || lower.all { it.isDigit() })
                }.distinctBy { it.lowercase() }

        return PlaylistRequest(
            text = original,
            durationMinutes = duration?.takeIf { it > 0 },
            songCount = count,
            artist = artist,
            artistMode = mode,
            artistAmbiguous = ambiguous,
            likedOnly = liked,
            familiarity = familiarity,
            forgotten = forgotten,
            categoryWords = words,
        )
    }

    /** Reads the artist name after a keyword, or null when what follows is not a name. */
    private fun cleanName(raw: String): String? {
        val trimmed = raw.trim().trim('"', '\'', ' ')
        if (trimmed.isEmpty() || !trimmed.first().isLetter()) return null
        val tokens = trimmed.split(Regex("\\s+")).toMutableList()
        if (tokens.first().lowercase() in nameDrop) {
            while (tokens.isNotEmpty() && tokens.first().lowercase() in nameDrop) tokens.removeAt(0)
            // "songs by X" / "שירים של X" names an artist; "שירים שמחים" does not.
            if (tokens.isEmpty() || tokens.first().lowercase() !in connectors) return null
            tokens.removeAt(0)
        }
        while (tokens.isNotEmpty() && tokens.last().lowercase().let { it in nameDrop || it in connectors }) {
            tokens.removeAt(tokens.lastIndex)
        }
        val name = tokens.joinToString(" ").trim().trim('"', '\'', '-', ' ')
        return name.takeIf { it.isNotEmpty() && it.first().isLetter() }
    }

    fun normalize(text: String): String =
        text
            .replace('׳', '\'')
            .replace('״', '"')
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('`', '\'')
            .replace('־', '-')
            .replace('–', '-')
            .replace(Regex("\\s+"), " ")
            .trim()

    private val finalForms = mapOf('ם' to 'מ', 'ן' to 'נ', 'ץ' to 'צ', 'ף' to 'פ', 'ך' to 'כ')
    private val hebrewSuffixes = listOf("יות", "ימ", "ות", "ית", "ה", "י")
    private const val HEBREW_PREFIXES = "והלבשמכ"

    /**
     * A tiny stemmer so that Hebrew prefixes (ה, ל, ב, ו, ש, מ, כ) and plural or feminine suffixes
     * (ים, ות, ית, ה) do not break category matching. Applied the same way to both sides.
     */
    fun stem(word: String): String {
        var w = word.lowercase().filter { it.isLetterOrDigit() }.map { finalForms[it] ?: it }.joinToString("")
        if (w.any { it in 'א'..'ת' }) {
            repeat(2) { round ->
                val minLeft = if (round == 0) 2 else 3
                val suffix = hebrewSuffixes.firstOrNull { w.endsWith(it) && w.length - it.length >= minLeft }
                if (suffix != null) w = w.dropLast(suffix.length) else return@repeat
            }
            var stripped = 0
            while (stripped < 2 && w.length - 1 >= 3 && w.first() in HEBREW_PREFIXES) {
                w = w.drop(1)
                stripped++
            }
        } else {
            w =
                when {
                    w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
                    w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
                    else -> w
                }
        }
        return w
    }

    /** Splits a title into lowercase words, without stopwords. */
    fun words(text: String): List<String> =
        text
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.lowercase() }
            .filter { it.isNotBlank() && it !in stopwords && (it.length >= 2 || it.all(Char::isDigit)) }
}
