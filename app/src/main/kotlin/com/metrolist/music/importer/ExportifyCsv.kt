/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.importer

import java.util.Locale

/** One row of a Spotify playlist exported with Exportify (exportify.net). */
data class SpotifyTrack(
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    val explicit: Boolean? = null,
) {
    val mainArtist: String?
        get() = artists.firstOrNull()

    /** "Title — Artist, Artist" for lists shown to the user. */
    val display: String
        get() = if (artists.isEmpty()) title else "$title — ${artists.joinToString(", ")}"
}

data class ExportifyFile(
    /** Tracks in file order, each listed once. */
    val tracks: List<SpotifyTrack>,
    /** Later copies of a track that already appears earlier in the file. */
    val duplicatesInFile: List<SpotifyTrack>,
    /** Rows without a track name (local files, podcasts with blank fields, broken rows). */
    val skippedRows: Int,
)

/**
 * Reads Exportify CSV files. Column order is not fixed and extra columns are ignored; current
 * ("Artist Name(s)", "Duration (ms)") and older ("Track Duration (ms)") header names both work.
 */
object ExportifyCsv {
    private val titleHeaders = listOf("track name")
    private val artistHeaders = listOf("artist name(s)", "artist name", "artist names", "artists")
    private val albumHeaders = listOf("album name")
    private val durationHeaders = listOf("duration (ms)", "track duration (ms)")
    private val explicitHeaders = listOf("explicit")

    /** Columns that only Exportify (Spotify exports) write. */
    private val spotifyMarkers = listOf("track uri", "artist uri(s)", "album uri", "spotify id")

    private fun key(header: String) = header.trim().trim('﻿').lowercase(Locale.ROOT)

    /**
     * True for an Exportify header: it must name the track and its artists, and carry either a
     * Spotify URI column or the Exportify duration column. A generic "title,artist" CSV is not one.
     */
    fun isExportifyHeader(header: List<String>): Boolean {
        val keys = header.map(::key)
        val hasTitle = keys.any { it in titleHeaders }
        val hasArtist = keys.any { it in artistHeaders }
        val hasMarker = keys.any { it in spotifyMarkers } || keys.any { it in durationHeaders }
        return hasTitle && hasArtist && hasMarker
    }

    fun isExportify(text: String): Boolean {
        val header = CsvParser.parse(text).firstOrNull() ?: return false
        return isExportifyHeader(header)
    }

    /** Parses an Exportify file, or returns null when the header is not an Exportify header. */
    fun parse(text: String): ExportifyFile? {
        val records = CsvParser.parse(text)
        val header = records.firstOrNull() ?: return null
        if (!isExportifyHeader(header)) return null
        val keys = header.map(::key)

        fun column(names: List<String>) = keys.indexOfFirst { it in names }

        val titleIndex = column(titleHeaders)
        val artistIndex = column(artistHeaders)
        val albumIndex = column(albumHeaders)
        val durationIndex = column(durationHeaders)
        val explicitIndex = column(explicitHeaders)

        val tracks = ArrayList<SpotifyTrack>()
        val duplicates = ArrayList<SpotifyTrack>()
        val seen = HashSet<String>()
        var skipped = 0

        for (row in records.drop(1)) {
            fun cell(index: Int) = row.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }

            val title = cell(titleIndex)
            if (title == null) {
                skipped++
                continue
            }
            val track =
                SpotifyTrack(
                    title = title,
                    artists = splitArtists(cell(artistIndex)),
                    album = cell(albumIndex),
                    durationMs = cell(durationIndex)?.toLongOrNull()?.takeIf { it > 0 },
                    explicit = cell(explicitIndex)?.lowercase(Locale.ROOT)?.let { it == "true" || it == "1" || it == "yes" },
                )
            if (seen.add(dedupeKey(track))) tracks.add(track) else duplicates.add(track)
        }
        return ExportifyFile(tracks, duplicates, skipped)
    }

    /** Exportify separates artists with ", " (newer) or ";" (some versions). */
    fun splitArtists(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val separator = if (value.contains(';')) ";" else ","
        return value.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Same song in one file: same normalized title and main artist. */
    fun dedupeKey(track: SpotifyTrack): String =
        TrackText.normalize(track.title) + "|" + TrackText.normalize(track.mainArtist.orEmpty())

    /** "My_Playlist.csv" → "My Playlist". */
    fun playlistNameFromFileName(fileName: String?): String {
        val base =
            fileName
                ?.substringAfterLast('/')
                ?.let { if (it.lowercase(Locale.ROOT).endsWith(".csv")) it.dropLast(4) else it }
                .orEmpty()
        return base.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }
}
