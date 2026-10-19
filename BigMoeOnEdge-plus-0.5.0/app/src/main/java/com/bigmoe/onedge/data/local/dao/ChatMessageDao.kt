package com.bigmoe.onedge.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bigmoe.onedge.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    // rowid breaks ties between messages saved within the same millisecond, keeping insertion order.
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC, rowid ASC")
    fun observeMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC, rowid ASC")
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Delete
    suspend fun deleteMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    /** Removes the given messages of one chat (callers pass chunks well below SQLite's 999-variable limit). */
    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId AND id IN (:ids)")
    suspend fun deleteMessagesByIds(sessionId: String, ids: List<String>): Int

    @Query("SELECT COUNT(*) FROM chat_messages WHERE session_id = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    @Query("UPDATE chat_messages SET content = :content WHERE id = :messageId AND role = 'USER'")
    suspend fun updateUserMessageContent(messageId: String, content: String): Int

    @Query("UPDATE chat_messages SET generation_elapsed_ms = :elapsedMs, context_used = :contextUsed, context_total = :contextTotal, generated_tokens = :generatedTokens WHERE id = :messageId")
    suspend fun updateGenerationTelemetry(
        messageId: String,
        elapsedMs: Long,
        contextUsed: Int,
        contextTotal: Int,
        generatedTokens: Int
    )
}
