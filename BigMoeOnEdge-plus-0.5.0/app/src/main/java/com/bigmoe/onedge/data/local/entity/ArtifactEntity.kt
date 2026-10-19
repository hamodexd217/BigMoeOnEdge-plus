package com.bigmoe.onedge.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Something the AI produced as a whole (a file, a document, a page), shown apart from the chat text.
 * The content is the workspace file at [path]; this row is the link to the chat that made it.
 */
@Entity(
    tableName = "artifacts",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["session_id"]), Index(value = ["path"])]
)
data class ArtifactEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    /** The assistant message that created it (empty when created from a code block or by hand). */
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "title")
    val title: String,

    /** Path relative to the workspace root. */
    @ColumnInfo(name = "path")
    val path: String,

    @ColumnInfo(name = "language")
    val language: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
