/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.importer

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {
    private fun song(
        id: String,
        title: String,
        vararg artists: String,
        seconds: Int? = null,
        explicit: Boolean = false,
        video: Boolean = false,
    ) = SongItem(
        id = id,
        title = title,
        artists = artists.map { Artist(it, null) },
        duration = seconds,
        thumbnail = "",
        explicit = explicit,
        musicVideoType = if (video) "MUSIC_VIDEO_TYPE_OMV" else null,
    )

    private fun track(
        title: String,
        vararg artists: String,
        ms: Long? = null,
    ) = SpotifyTrack(title = title, artists = artists.toList(), durationMs = ms)

    private fun matchedId(result: MatchResult): String? = (result as? MatchResult.Matched)?.song?.id

    @Test
    fun `exact title and artist wins over a cover with the same title`() {
        val result =
            TrackMatcher.match(
                track("Blinding Lights", "The Weeknd", ms = 200_040),
                listOf(
                    song("cover", "Blinding Lights", "Piano Covers Band", seconds = 200),
                    song("real", "Blinding Lights", "The Weeknd", seconds = 200),
                ),
            )
        assertEquals("real", matchedId(result))
        assertTrue((result as MatchResult.Matched).confidence >= TrackMatcher.THRESHOLD)
    }

    @Test
    fun `feat in brackets, after a dash and without brackets`() {
        val peaches = song("p", "Peaches (feat. Daniel Caesar and Giveon)", "Justin Bieber", seconds = 198)
        assertEquals("p", matchedId(TrackMatcher.match(track("Peaches (feat. Daniel Caesar & Giveon)", "Justin Bieber", "Daniel Caesar", "Giveon", ms = 198_082), listOf(peaches))))
        assertEquals("p", matchedId(TrackMatcher.match(track("Peaches - feat. Giveon", "Justin Bieber"), listOf(peaches))))
        assertEquals("p", matchedId(TrackMatcher.match(track("Peaches feat. Giveon", "Justin Bieber"), listOf(peaches))))
    }

    @Test
    fun `remastered and live notes, accents and punctuation`() {
        val sun = song("s", "Here Comes the Sun", "The Beatles", seconds = 186)
        assertEquals("s", matchedId(TrackMatcher.match(track("Here Comes The Sun - Remastered 2009", "The Beatles", ms = 185_733), listOf(sun))))
        val senorita = song("x", "Senorita", "Shawn Mendes & Camila Cabello", seconds = 191)
        assertEquals("x", matchedId(TrackMatcher.match(track("Señorita", "Shawn Mendes", "Camila Cabello", ms = 190_960), listOf(senorita))))
        val dont = song("d", "Don't Start Now", "Dua Lipa", seconds = 183)
        assertEquals("d", matchedId(TrackMatcher.match(track("Dont Start Now", "Dua Lipa", ms = 183_290), listOf(dont))))
    }

    @Test
    fun `suffix that is part of the name`() {
        val sunflower = song("f", "Sunflower (Spider-Man: Into the Spider-Verse)", "Post Malone", "Swae Lee", seconds = 158)
        assertEquals(
            "f",
            matchedId(TrackMatcher.match(track("Sunflower - Spider-Man: Into the Spider-Verse", "Post Malone", "Swae Lee", ms = 158_040), listOf(sunflower))),
        )
    }

    @Test
    fun `Hebrew title with niqqud matches the plain spelling`() {
        val result =
            TrackMatcher.match(
                track("שִׁיר לַשָּׁלוֹם", "להקת הנח\"ל", ms = 240_000),
                listOf(song("h", "שיר לשלום", "להקת הנחל", seconds = 241)),
            )
        assertEquals("h", matchedId(result))
    }

    @Test
    fun `artist in another script needs the same title and nearly the same length`() {
        val candidate = song("t", "תל אביב", "עומר אדם", seconds = 191)
        assertEquals("t", matchedId(TrackMatcher.match(track("תל אביב", "Omer Adam", ms = 190_000), listOf(candidate))))
        assertEquals(
            MatchResult.NotFound(NotFoundReason.NoConfidentMatch),
            TrackMatcher.match(track("תל אביב", "Omer Adam", ms = 200_000), listOf(candidate)),
        )
        assertEquals(
            MatchResult.NotFound(NotFoundReason.NoConfidentMatch),
            TrackMatcher.match(track("תל אביב", "Omer Adam"), listOf(candidate)),
        )
    }

    @Test
    fun `wrong artist is rejected even with the same title and length`() {
        assertEquals(
            MatchResult.NotFound(NotFoundReason.NoConfidentMatch),
            TrackMatcher.match(track("Hello", "Adele", ms = 295_000), listOf(song("l", "Hello", "Lionel Richie", seconds = 295))),
        )
    }

    @Test
    fun `different title is rejected`() {
        assertEquals(
            MatchResult.NotFound(NotFoundReason.NoConfidentMatch),
            TrackMatcher.match(track("Love Story", "Taylor Swift"), listOf(song("x", "Love", "Taylor Swift"), song("y", "Shake It Off", "Taylor Swift"))),
        )
    }

    @Test
    fun `far off length is rejected, close length is preferred`() {
        assertEquals(
            MatchResult.NotFound(NotFoundReason.NoConfidentMatch),
            TrackMatcher.match(track("Yellow", "Coldplay", ms = 266_000), listOf(song("long", "Yellow", "Coldplay", seconds = 340))),
        )
        assertEquals(
            "close",
            matchedId(
                TrackMatcher.match(
                    track("Yellow", "Coldplay", ms = 266_000),
                    listOf(song("off", "Yellow", "Coldplay", seconds = 290), song("close", "Yellow", "Coldplay", seconds = 267)),
                ),
            ),
        )
    }

    @Test
    fun `karaoke and live versions are not taken for the studio track`() {
        assertEquals(
            MatchResult.NotFound(NotFoundReason.NoConfidentMatch),
            TrackMatcher.match(
                track("Shape of You", "Ed Sheeran", ms = 233_700),
                listOf(song("k", "Shape of You (Karaoke Version)", "Ed Sheeran", seconds = 234)),
            ),
        )
        assertEquals(
            "studio",
            matchedId(
                TrackMatcher.match(
                    track("Shape of You", "Ed Sheeran", ms = 233_700),
                    listOf(song("live", "Shape of You (Live)", "Ed Sheeran", seconds = 236), song("studio", "Shape of You", "Ed Sheeran", seconds = 234)),
                ),
            ),
        )
    }

    @Test
    fun `no candidates`() {
        assertEquals(MatchResult.NotFound(NotFoundReason.NoResults), TrackMatcher.match(track("Anything", "Anyone"), emptyList()))
    }

    @Test
    fun `audio song is preferred over its music video`() {
        val result =
            TrackMatcher.match(
                track("Levitating", "Dua Lipa", ms = 203_000),
                listOf(song("video", "Levitating", "Dua Lipa", seconds = 203, video = true), song("audio", "Levitating", "Dua Lipa", seconds = 203)),
            )
        assertEquals("audio", matchedId(result))
    }

    @Test
    fun `hidden explicit songs - clean version chosen, otherwise not found with the reason`() {
        val t = track("HUMBLE.", "Kendrick Lamar", ms = 177_000)
        val explicit = song("e", "HUMBLE.", "Kendrick Lamar", seconds = 177, explicit = true)
        val clean = song("c", "HUMBLE.", "Kendrick Lamar", seconds = 177)
        assertEquals("c", matchedId(TrackMatcher.match(t, listOf(explicit, clean), MatchFilters(hideExplicit = true))))
        assertEquals(
            MatchResult.NotFound(NotFoundReason.OnlyExplicit),
            TrackMatcher.match(t, listOf(explicit), MatchFilters(hideExplicit = true)),
        )
        assertEquals("e", matchedId(TrackMatcher.match(t, listOf(explicit), MatchFilters(hideExplicit = false))))
    }

    @Test
    fun `hidden video songs - only a video match is reported`() {
        assertEquals(
            MatchResult.NotFound(NotFoundReason.OnlyVideo),
            TrackMatcher.match(
                track("Levitating", "Dua Lipa", ms = 203_000),
                listOf(song("video", "Levitating", "Dua Lipa", seconds = 203, video = true)),
                MatchFilters(hideVideoSongs = true),
            ),
        )
    }

    @Test
    fun `search query uses the title without edition notes and the main artist`() {
        assertEquals("Peaches Justin Bieber", TrackMatcher.query(track("Peaches (feat. Daniel Caesar & Giveon)", "Justin Bieber", "Giveon")))
        assertEquals("Here Comes The Sun The Beatles", TrackMatcher.query(track("Here Comes The Sun - Remastered 2009", "The Beatles")))
        assertEquals("Without Me Eminem", TrackMatcher.query(track("Without Me", "Eminem")))
        assertEquals("(Without You) Someone", TrackMatcher.query(track("(Without You)", "Someone")))
    }

    @Test
    fun `core title strips edition notes only`() {
        assertEquals("yesterday", TrackText.coreTitle("Yesterday - Remastered 2009"))
        assertEquals("song", TrackText.coreTitle("Song - Live"))
        assertEquals("song", TrackText.coreTitle("Song (feat. X) [Radio Edit]"))
        assertEquals("rock n roll train", TrackText.coreTitle("Rock 'n' Roll Train"))
        assertEquals("sunflower spider man into the spider verse", TrackText.coreTitle("Sunflower - Spider-Man: Into the Spider-Verse"))
        assertEquals("שיר לשלום", TrackText.normalize("שִׁיר לַשָּׁלוֹם"))
    }
}
