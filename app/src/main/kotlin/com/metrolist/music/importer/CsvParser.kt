/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.importer

/**
 * RFC 4180 CSV reader: quoted fields may contain commas, line breaks and doubled quotes (`""`).
 * Accepts LF, CRLF and CR line endings and a leading UTF-8 byte order mark.
 */
object CsvParser {
    private const val BOM = '﻿'

    /** Parses the whole text into records. Blank lines are skipped. */
    fun parse(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldStarted = false
        var i = if (text.startsWith(BOM)) 1 else 0

        fun endField() {
            fields.add(field.toString())
            field.setLength(0)
            fieldStarted = false
        }

        fun endRecord() {
            endField()
            if (!(fields.size == 1 && fields[0].isEmpty())) records.add(ArrayList(fields))
            fields.clear()
        }

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' ->
                        if (field.isBlank()) {
                            // Spaces before an opening quote are not part of the value.
                            field.setLength(0)
                            inQuotes = true
                            fieldStarted = true
                        } else {
                            // A stray quote inside an unquoted field is kept as text.
                            field.append(c)
                        }
                    ',' -> endField()
                    '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        endRecord()
                    }
                    '\n' -> endRecord()
                    else -> {
                        field.append(c)
                        fieldStarted = true
                    }
                }
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty() || fieldStarted) endRecord()
        return records
    }

    /** Parses one line (no line breaks inside quotes) into trimmed fields. */
    fun parseLine(line: String): List<String> =
        parse(line).firstOrNull()?.map { it.trim() } ?: listOf("")
}
