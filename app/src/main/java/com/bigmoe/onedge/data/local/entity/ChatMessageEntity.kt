package com.bigmoe.onedge.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["session_id", "timestamp"])]
)
data class ChatMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "role")
    val role: MessageRole,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    /** The model's reasoning span for this turn (assistant only), null when none. */
    @ColumnInfo(name = "reasoning")
    val reasoning: String? = null,

    // Telemetry from the engine's RunSummary (assistant messages only).
    @ColumnInfo(name = "tokens_per_second")
    val tokensPerSecond: Double? = null,

    /** Prefill time of the turn in ms (time until the first token could be produced). */
    @ColumnInfo(name = "prefill_ms")
    val prefillMs: Long? = null,

    @ColumnInfo(name = "generated_tokens")
    val generatedTokens: Int? = null,

    /** Expert cache hit rate in percent, null when the cache is off. */
    @ColumnInfo(name = "cache_hit_pct")
    val cacheHitPct: Double? = null,

    /** Set when the turn ended with an error or was stopped by the user, for display. */
    @ColumnInfo(name = "note")
    val note: String? = null
)
