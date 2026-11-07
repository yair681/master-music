/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvParserTest {
    @Test
    fun `quoted fields keep commas`() {
        assertEquals(
            listOf(listOf("a", "b, c", "d")),
            CsvParser.parse("a,\"b, c\",d"),
        )
    }

    @Test
    fun `doubled quotes become one quote`() {
        assertEquals(
            listOf(listOf("He said \"hi\"", "x")),
            CsvParser.parse("\"He said \"\"hi\"\"\",x"),
        )
    }

    @Test
    fun `byte order mark is dropped`() {
        assertEquals(listOf(listOf("Track Name", "x")), CsvParser.parse("﻿Track Name,x"))
    }

    @Test
    fun `CRLF LF and CR line endings and blank lines`() {
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d"), listOf("e", "f"), listOf("g", "h")),
            CsvParser.parse("a,b\r\nc,d\n\ne,f\rg,h\r\n"),
        )
    }

    @Test
    fun `line breaks inside quotes stay in the field`() {
        assertEquals(
            listOf(listOf("1", "line one\nline two"), listOf("2", "x")),
            CsvParser.parse("1,\"line one\nline two\"\r\n2,x"),
        )
    }

    @Test
    fun `empty fields are kept`() {
        assertEquals(listOf(listOf("a", "", "", "d", "")), CsvParser.parse("a,,\"\",d,"))
    }

    @Test
    fun `parseLine trims like the generic CSV import`() {
        assertEquals(listOf("Artist", "Title, with comma", "x"), CsvParser.parseLine(" Artist , \"Title, with comma\" ,x"))
    }

    @Test
    fun `Hebrew text survives`() {
        assertEquals(listOf(listOf("שיר, אהבה", "עומר אדם")), CsvParser.parse("\"שיר, אהבה\",עומר אדם"))
    }
}
