package com.bigmoe.onedge.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** Bumped on every new message; History sorts by it. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /** Path of the model file that was loaded when the chat was created. */
    @ColumnInfo(name = "model_path")
    val modelPath: String? = null,

    /** JSON array of distinct model file names used by this conversation. */
    @ColumnInfo(name = "model_names_json")
    val modelNamesJson: String = "[]",

    /** Persisted generation metadata; transient engine objects are never stored. */
    @ColumnInfo(name = "generation_state")
    val generationState: String = "IDLE",
    @ColumnInfo(name = "generation_started_at")
    val generationStartedAt: Long? = null,
    @ColumnInfo(name = "generation_elapsed_ms")
    val generationElapsedMs: Long = 0L,
    @ColumnInfo(name = "generation_tokens")
    val generationTokens: Int = 0,
    @ColumnInfo(name = "context_used")
    val contextUsed: Int = 0,
    @ColumnInfo(name = "context_total")
    val contextTotal: Int = 0,
    @ColumnInfo(name = "generation_note")
    val generationNote: String? = null
)
