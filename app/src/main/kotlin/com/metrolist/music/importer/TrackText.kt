/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.importer

import java.text.Normalizer
import java.util.Locale

/** Text normalization shared by the Exportify reader and the track matcher. */
object TrackText {
    private val combiningMarks = Regex("\\p{M}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")

    /** Bracketed parts: "(feat. X)", "[Remastered 2011]", "{Live}". */
    private val bracketed = Regex("\\s*[(\\[{][^)\\]}]*[)\\]}]")

    /** "feat. X" / "ft. X" / "featuring X" outside brackets, to the end of the title. */
    private val featTail = Regex("(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$")

    /** Words that mark a " - suffix" as an edition note rather than part of the name. */
    private val editionWords =
        listOf(
            "remaster", "remastered", "live", "version", "edit", "mono", "stereo", "feat", "ft",
            "featuring", "from", "bonus", "deluxe", "anniversary", "mix", "acoustic", "demo",
            "recorded", "session", "explicit", "radio", "extended", "instrumental", "remix",
            "unplugged", "edition",
        ).toSet()

    /** Lower case, without accents, Hebrew niqqud and cantillation, and punctuation. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        return decomposed
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .replace(nonWord, " ")
            .replace(spaces, " ")
            .trim()
    }

    /**
     * The title without edition notes: "Yesterday - Remastered 2009" → "Yesterday",
     * "Song (feat. X)" → "Song", "Song - Live" → "Song". Never returns an empty string when
     * the input had text; a title that is all brackets is kept as is.
     */
    fun coreTitle(title: String): String {
        var result = title.replace(bracketed, " ")
        result = result.replace(featTail, "")
        val dash = Regex("\\s[-–—]\\s").findAll(result).lastOrNull()
        if (dash != null) {
            val suffix = normalize(result.substring(dash.range.last + 1))
            val words = suffix.split(' ').filter { it.isNotEmpty() }
            if (words.isNotEmpty() && words.any { w -> w in editionWords || w.startsWith("remaster") }) {
                result = result.substring(0, dash.range.first)
            }
        }
        val cleaned = normalize(result)
        return cleaned.ifEmpty { normalize(title) }
    }

    /** Variant markers that make two recordings of the same song different. */
    val variantWords =
        listOf(
            "remix", "live", "acoustic", "instrumental", "karaoke", "cover", "sped", "slowed",
            "nightcore", "8d", "reverb", "unplugged", "demo",
        )

    /** Markers that almost never belong to the studio track a playlist meant. */
    val unwantedWords = listOf("karaoke", "cover", "sped", "slowed", "nightcore", "8d", "reverb", "instrumental")

    fun variants(title: String): Set<String> {
        val words = normalize(title).split(' ').toSet()
        return variantWords.filter { it in words }.toSet()
    }

    fun hasLatinLetters(text: String): Boolean = text.any { it in 'a'..'z' || it in 'A'..'Z' }

    /** Hebrew, Arabic, Cyrillic and other scripts beyond Latin Extended. */
    fun hasNonLatinLetters(text: String): Boolean = text.any { it.isLetter() && it.code > 0x24F }
}
