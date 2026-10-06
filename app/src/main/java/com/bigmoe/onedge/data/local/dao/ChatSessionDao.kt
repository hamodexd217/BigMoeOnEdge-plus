package com.bigmoe.onedge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bigmoe.onedge.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {

    @Query("SELECT * FROM chat_sessions ORDER BY updated_at DESC")
    fun observeAllSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    fun observeSessionById(sessionId: String): Flow<ChatSessionEntity?>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: String, title: String)

    @Query("UPDATE chat_sessions SET updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun touchSession(sessionId: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET model_names_json = :json WHERE id = :sessionId")
    suspend fun updateModelNames(sessionId: String, json: String)

    @Query("UPDATE chat_sessions SET generation_state = 'RUNNING', generation_started_at = :startedAt, generation_elapsed_ms = :elapsedMs, generation_tokens = :tokens, context_used = :contextUsed, context_total = :contextTotal, generation_note = NULL WHERE id = :sessionId")
    suspend fun updateGenerationProgress(sessionId: String, startedAt: Long, elapsedMs: Long, tokens: Int, contextUsed: Int, contextTotal: Int)

    @Query("UPDATE chat_sessions SET generation_state = :state, generation_elapsed_ms = :elapsedMs, generation_tokens = :tokens, context_used = :contextUsed, context_total = :contextTotal, generation_note = :note WHERE id = :sessionId")
    suspend fun finishGeneration(sessionId: String, elapsedMs: Long, tokens: Int, contextUsed: Int, contextTotal: Int, note: String?, state: String)

    @Query("UPDATE chat_sessions SET generation_state = 'IDLE', generation_started_at = NULL WHERE generation_state = 'RUNNING'")
    suspend fun clearTransientGenerationStates()

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()
}
