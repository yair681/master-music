/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRequestParserTest {
    private fun parse(text: String) = PlaylistRequestParser.parse(text)

    @Test
    fun `hebrew duration with style artist`() {
        val r = parse("תיצור לי פלייליסט של 45 דקות בסגנון חנן בן ארי")
        assertEquals(45.0, r.durationMinutes!!, 1e-9)
        assertEquals(45 * 60_000L, r.targetMs)
        assertEquals("חנן בן ארי", r.artist)
        assertEquals(ArtistMode.STYLE, r.artistMode)
        assertNull(r.songCount)
        assertEquals(emptyList<String>(), r.categoryWords)
        assertFalse(r.likedOnly)
        assertEquals(Familiarity.ANY, r.familiarity)
    }

    @Test
    fun `possessive names only that artist`() {
        val r = parse("פלייליסט של חנן בן ארי")
        assertEquals("חנן בן ארי", r.artist)
        assertEquals(ArtistMode.ONLY, r.artistMode)
        assertTrue(r.artistAmbiguous)
        assertNull(r.durationMinutes)
    }

    @Test
    fun `only keyword is not ambiguous`() {
        val r = parse("רק עומר אדם")
        assertEquals("עומר אדם", r.artist)
        assertEquals(ArtistMode.ONLY, r.artistMode)
        assertFalse(r.artistAmbiguous)
    }

    @Test
    fun `songs of an artist after a duration`() {
        val r = parse("שעתיים של שירים של עומר אדם")
        assertEquals(120.0, r.durationMinutes!!, 1e-9)
        assertEquals("עומר אדם", r.artist)
        assertEquals(ArtistMode.ONLY, r.artistMode)
    }

    @Test
    fun `liked and forgotten flags`() {
        val r = parse("שירים שאהבתי ולא שמעתי מזמן")
        assertTrue(r.likedOnly)
        assertTrue(r.forgotten)
        assertEquals(Familiarity.ANY, r.familiarity)
        assertNull(r.artist)
        assertEquals(emptyList<String>(), r.categoryWords)
        assertTrue(r.usesLocalHistory)
    }

    @Test
    fun `an hour of new songs`() {
        val r = parse("שעה של שירים חדשים")
        assertEquals(60.0, r.durationMinutes!!, 1e-9)
        assertEquals(Familiarity.NEW_ONLY, r.familiarity)
        assertNull(r.artist)
        assertEquals(emptyList<String>(), r.categoryWords)
    }

    @Test
    fun `half an hour keeps the mood word`() {
        val r = parse("חצי שעה של מוזיקה רגועה")
        assertEquals(30.0, r.durationMinutes!!, 1e-9)
        assertNull(r.artist)
        assertEquals(listOf("רגועה"), r.categoryWords)
    }

    @Test
    fun `decimal hours and style with possessive`() {
        val r = parse("1.5 שעות בסגנון של עידן רייכל")
        assertEquals(90.0, r.durationMinutes!!, 1e-9)
        assertEquals("עידן רייכל", r.artist)
        assertEquals(ArtistMode.STYLE, r.artistMode)
    }

    @Test
    fun `song count with like`() {
        val r = parse("20 שירים כמו אייל גולן")
        assertEquals(20, r.songCount)
        assertNull(r.durationMinutes)
        assertEquals("אייל גולן", r.artist)
        assertEquals(ArtistMode.STYLE, r.artistMode)
    }

    @Test
    fun `short hebrew minutes and possessive genre is ambiguous`() {
        val r = parse("45 דק' של רוק")
        assertEquals(45.0, r.durationMinutes!!, 1e-9)
        assertEquals("רוק", r.artist)
        assertTrue(r.artistAmbiguous)
    }

    @Test
    fun `english minutes with like`() {
        val r = parse("Make me a 30 minute playlist like Coldplay")
        assertEquals(30.0, r.durationMinutes!!, 1e-9)
        assertEquals("Coldplay", r.artist)
        assertEquals(ArtistMode.STYLE, r.artistMode)
        assertEquals(emptyList<String>(), r.categoryWords)
    }

    @Test
    fun `english hours by artist`() {
        val r = parse("2 hours of songs by Eyal Golan")
        assertEquals(120.0, r.durationMinutes!!, 1e-9)
        assertEquals("Eyal Golan", r.artist)
        assertEquals(ArtistMode.ONLY, r.artistMode)
    }

    @Test
    fun `english count similar to stops at a separator`() {
        val r = parse("20 songs similar to Adele without ballads")
        assertEquals(20, r.songCount)
        assertEquals("Adele", r.artist)
        assertEquals(ArtistMode.STYLE, r.artistMode)
    }

    @Test
    fun `in the style of with an hour`() {
        val r = parse("A 45 minute playlist in the style of Coldplay")
        assertEquals(45.0, r.durationMinutes!!, 1e-9)
        assertEquals("Coldplay", r.artist)
        assertEquals(ArtistMode.STYLE, r.artistMode)
    }

    @Test
    fun `only songs by artist`() {
        val r = parse("only songs by Queen")
        assertEquals("Queen", r.artist)
        assertEquals(ArtistMode.ONLY, r.artistMode)
    }

    @Test
    fun `only new songs is a flag and not an artist`() {
        val r = parse("only new songs")
        assertNull(r.artist)
        assertEquals(Familiarity.NEW_ONLY, r.familiarity)
    }

    @Test
    fun `english liked and forgotten`() {
        val r = parse("Liked songs I haven't heard in a while")
        assertTrue(r.likedOnly)
        assertTrue(r.forgotten)
        assertNull(r.artist)
    }

    @Test
    fun `english familiar for an hour`() {
        val r = parse("familiar songs for an hour")
        assertEquals(Familiarity.FAMILIAR_ONLY, r.familiarity)
        assertEquals(60.0, r.durationMinutes!!, 1e-9)
    }

    @Test
    fun `hebrew familiar and liked words`() {
        assertEquals(Familiarity.FAMILIAR_ONLY, parse("שירים שאני מכיר").familiarity)
        assertTrue(parse("האהובים שלי").likedOnly)
        assertTrue(parse("שירים נשכחים").forgotten)
        assertEquals(Familiarity.NEW_ONLY, parse("שירים שלא שמעתי").familiarity)
    }

    @Test
    fun `english count of tracks`() {
        assertEquals(25, parse("playlist of 25 tracks").songCount)
    }

    @Test
    fun `remaining words become category words`() {
        val r = parse("פלייליסט לריצה")
        assertEquals(listOf("לריצה"), r.categoryWords)
        assertTrue(r.isRecognized)
        assertNull(r.artist)
    }

    @Test
    fun `nothing recognized`() {
        assertFalse(parse("תיצור לי פלייליסט").isRecognized)
        assertFalse(parse("please make me a playlist").isRecognized)
        assertFalse(parse("   ").isRecognized)
    }

    @Test
    fun `stem removes hebrew prefixes and plural suffixes`() {
        assertEquals("שיר", PlaylistRequestParser.stem("שירים"))
        assertEquals(PlaylistRequestParser.stem("שירים"), PlaylistRequestParser.stem("השירים"))
        assertEquals("רוק", PlaylistRequestParser.stem("לרוק"))
        assertEquals(PlaylistRequestParser.stem("רגועה"), PlaylistRequestParser.stem("רגועים"))
        assertEquals(PlaylistRequestParser.stem("מזרחי"), PlaylistRequestParser.stem("מזרחית"))
        assertEquals("workout", PlaylistRequestParser.stem("Workouts"))
        assertEquals("שמח", PlaylistRequestParser.stem("שמח"))
    }
}
