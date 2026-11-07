/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Append-only log of explicit and implicit taste signals for a song. Stored only on the device.
 *
 * Likes are not duplicated here: `song.liked` / `song.likedDate` stay the single source of truth,
 * because they are also written by menus, sync and widgets.
 */
@Immutable
@Entity(
    tableName = "song_feedback",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["songId", "type"])],
)
data class SongFeedback(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val type: FeedbackType,
    val timestamp: LocalDateTime,
    /** For SKIP: how far into the song the user left it. */
    val positionMs: Long? = null,
    /** For SKIP: song length when known. */
    val durationMs: Long? = null,
)

enum class FeedbackType {
    DISLIKE,
    UNDISLIKE,
    MORE_LIKE_THIS,
    SKIP,
}
