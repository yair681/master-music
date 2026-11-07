/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.FeedbackType
import com.metrolist.music.db.entities.SongFeedback
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime

/**
 * Builds a real version 38 database from the exported schema, fills it with user data and opens it
 * with the current Room database, which must run the 38 -> 39 migration without losing anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SongFeedbackMigrationTest {
    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("migration-38-39.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbFile.name)
    }

    private fun createVersion38Database() {
        val schema = JSONObject(File("schemas/com.metrolist.music.db.InternalDatabase/38.json").readText())
        val database = schema.getJSONObject("database")
        assertEquals(38, database.getInt("version"))

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val views = database.optJSONArray("views")
            if (views != null) {
                for (i in 0 until views.length()) {
                    val view = views.getJSONObject(i)
                    db.execSQL(view.getString("createSql").replace("\${VIEW_NAME}", view.getString("viewName")))
                }
            }
            val setupQueries = database.getJSONArray("setupQueries")
            for (i in 0 until setupQueries.length()) {
                db.execSQL(setupQueries.getString(i))
            }

            db.execSQL(
                "INSERT INTO song (id, title, duration, liked, likedDate, totalPlayTime) " +
                    "VALUES ('kept', 'Kept song', 200, 1, 1000, 90000)",
            )
            db.execSQL("INSERT INTO event (songId, timestamp, playTime) VALUES ('kept', 2000, 90000)")
            db.version = 38
        }
    }

    @Test
    fun `migration keeps existing data and adds an empty feedback table`() =
        runBlocking {
            createVersion38Database()

            val database =
                Room
                    .databaseBuilder(context, InternalDatabase::class.java, dbFile.name)
                    .allowMainThreadQueries()
                    .build()
            try {
                val db = database.openHelper.writableDatabase
                assertEquals(39, db.version)

                db.query("SELECT title, liked, totalPlayTime FROM song WHERE id = 'kept'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Kept song", cursor.getString(0))
                    assertEquals(1, cursor.getInt(1))
                    assertEquals(90_000L, cursor.getLong(2))
                }
                db.query("SELECT COUNT(*) FROM event WHERE songId = 'kept'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM song_feedback").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }

                assertFalse(database.dao.isDislikedNow("kept"))
                database.dao.insert(
                    SongFeedback(songId = "kept", type = FeedbackType.DISLIKE, timestamp = LocalDateTime.now()),
                )
                assertTrue(database.dao.isDislikedNow("kept"))
            } finally {
                database.close()
            }
        }
}
