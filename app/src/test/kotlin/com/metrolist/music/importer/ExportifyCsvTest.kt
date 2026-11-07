/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportifyCsvTest {
    private val currentHeader =
        "\"Track URI\",\"Track Name\",\"Album Name\",\"Artist Name(s)\",\"Release Date\",\"Duration (ms)\"," +
            "\"Popularity\",\"Explicit\",\"Added By\",\"Added At\",\"Genres\",\"Record Label\",\"Danceability\""

    @Test
    fun `current Exportify export is parsed`() {
        val csv =
            "﻿$currentHeader\r\n" +
                "\"spotify:track:1\",\"Blinding Lights\",\"After Hours\",\"The Weeknd\",\"2020-03-20\",\"200040\",\"90\",\"false\",\"me\",\"2024-01-01\",\"pop\",\"Republic\",\"0.5\"\r\n" +
                "\"spotify:track:2\",\"Peaches (feat. Daniel Caesar & Giveon)\",\"Justice\",\"Justin Bieber, Daniel Caesar, Giveon\",\"2021\",\"198082\",\"80\",\"true\",\"\",\"\",\"\",\"\",\"\"\r\n"
        val file = ExportifyCsv.parse(csv)!!
        assertEquals(2, file.tracks.size)
        assertEquals(
            SpotifyTrack("Blinding Lights", listOf("The Weeknd"), "After Hours", 200040, false),
            file.tracks[0],
        )
        val peaches = file.tracks[1]
        assertEquals(listOf("Justin Bieber", "Daniel Caesar", "Giveon"), peaches.artists)
        assertEquals(true, peaches.explicit)
        assertEquals(198082L, peaches.durationMs)
    }

    @Test
    fun `older header names and a different column order`() {
        val csv =
            "Spotify ID,Artist URI(s),Album Name,Track Name,Artist Name(s),Track Duration (ms),Album Artist Name(s)\n" +
                "abc,spotify:artist:1,Abbey Road,Here Comes The Sun - Remastered 2009,The Beatles,185733,The Beatles\n"
        val file = ExportifyCsv.parse(csv)!!
        assertEquals(
            listOf(SpotifyTrack("Here Comes The Sun - Remastered 2009", listOf("The Beatles"), "Abbey Road", 185733, null)),
            file.tracks,
        )
    }

    @Test
    fun `semicolon separated artists`() {
        assertEquals(listOf("A", "B, Jr.", "C"), ExportifyCsv.splitArtists("A;B, Jr.;C"))
        assertEquals(listOf("A", "B"), ExportifyCsv.splitArtists("A, B"))
        assertEquals(emptyList<String>(), ExportifyCsv.splitArtists(" "))
    }

    @Test
    fun `quoted commas, escaped quotes and Hebrew`() {
        val csv =
            "Track URI,Track Name,Artist Name(s),Duration (ms)\n" +
                "x,\"Hello, \"\"Goodbye\"\"\",\"Tyler, The Creator\",1000\n" +
                "y,\"תל אביב\",\"עומר אדם\",190000\n"
        val file = ExportifyCsv.parse(csv)!!
        assertEquals("Hello, \"Goodbye\"", file.tracks[0].title)
        assertEquals("תל אביב", file.tracks[1].title)
        assertEquals(listOf("עומר אדם"), file.tracks[1].artists)
    }

    @Test
    fun `missing columns and short rows do not break parsing`() {
        val csv =
            "Track URI,Track Name,Artist Name(s)\n" +
                "x,Only Title\n" +
                "y,,Nobody\n" +
                "z,Song,Artist\n"
        val file = ExportifyCsv.parse(csv)!!
        assertEquals(listOf("Only Title", "Song"), file.tracks.map { it.title })
        assertEquals(emptyList<String>(), file.tracks[0].artists)
        assertNull(file.tracks[0].durationMs)
        assertEquals(1, file.skippedRows)
    }

    @Test
    fun `header detection tells Exportify from a generic CSV`() {
        assertTrue(ExportifyCsv.isExportify("﻿$currentHeader\n"))
        assertTrue(ExportifyCsv.isExportifyHeader(listOf("Track Name", "Artist Name(s)", "Track Duration (ms)")))
        assertFalse(ExportifyCsv.isExportify("Artist,Title\nA,B\n"))
        assertFalse(ExportifyCsv.isExportify("Track Name,Artist Name(s)\nA,B\n"))
        assertFalse(ExportifyCsv.isExportify("title,artist,url\n"))
        assertFalse(ExportifyCsv.isExportify(""))
        assertNull(ExportifyCsv.parse("Artist,Title\nA,B\n"))
    }

    @Test
    fun `repeated tracks are kept once`() {
        val csv =
            "Track URI,Track Name,Artist Name(s)\n" +
                "1,Yellow,Coldplay\n" +
                "2,YELLOW!,coldplay, Someone\n" +
                "3,Yellow - Live,Coldplay\n" +
                "4,Yellow,Other Band\n" +
                "5,Yellow,Coldplay\n"
        val file = ExportifyCsv.parse(csv)!!
        assertEquals(listOf("Yellow", "Yellow - Live", "Yellow"), file.tracks.map { it.title })
        assertEquals(listOf("Other Band"), file.tracks.drop(2).map { it.mainArtist })
        assertEquals(2, file.duplicatesInFile.size)
    }

    @Test
    fun `playlist name comes from the file name`() {
        assertEquals("My Playlist", ExportifyCsv.playlistNameFromFileName("My_Playlist.csv"))
        assertEquals("שירים לנסיעה", ExportifyCsv.playlistNameFromFileName("שירים_לנסיעה.CSV"))
        assertEquals("export", ExportifyCsv.playlistNameFromFileName("/storage/x/export"))
        assertEquals("", ExportifyCsv.playlistNameFromFileName(null))
    }
}
