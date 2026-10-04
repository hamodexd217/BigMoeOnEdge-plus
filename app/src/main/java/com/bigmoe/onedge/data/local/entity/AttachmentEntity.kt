package com.bigmoe.onedge.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** FILE = text/code document, IMAGE = picture, VIDEO = clip (kept for display; the model sees sampled frames). */
enum class AttachmentKind { FILE, IMAGE, VIDEO }

/** A file the user attached to a message. The bytes live in the workspace; this row links them to the chat. */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["session_id"]), Index(value = ["message_id"])]
)
data class AttachmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    /** The user message this belongs to. */
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "mime")
    val mime: String,

    /** Path relative to the workspace root, e.g. "uploads/report.md". */
    @ColumnInfo(name = "path")
    val path: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
