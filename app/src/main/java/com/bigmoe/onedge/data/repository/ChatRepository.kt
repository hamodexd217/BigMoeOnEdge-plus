package com.bigmoe.onedge.data.repository

import com.bigmoe.onedge.core.MessageStore
import com.bigmoe.onedge.core.NewMessage
import com.bigmoe.onedge.data.local.dao.AttachmentDao
import com.bigmoe.onedge.data.local.dao.ChatMessageDao
import com.bigmoe.onedge.data.local.dao.ChatSessionDao
import com.bigmoe.onedge.data.local.entity.AttachmentEntity
import com.bigmoe.onedge.data.local.entity.AttachmentKind
import com.bigmoe.onedge.data.local.entity.ChatMessageEntity
import com.bigmoe.onedge.data.local.entity.ChatSessionEntity
import com.bigmoe.onedge.data.local.entity.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class ChatSessionDomainModel(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelPath: String? = null
)

data class ChatMessageDomainModel(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val reasoning: String? = null,
    val tokensPerSecond: Double? = null,
    val prefillMs: Long? = null,
    val generatedTokens: Int? = null,
    val cacheHitPct: Double? = null,
    val note: String? = null
)

data class AttachmentDomainModel(
    val id: String,
    val sessionId: String,
    val messageId: String,
    val kind: AttachmentKind,
    val name: String,
    val mime: String,
    val path: String,
    val sizeBytes: Long
)

class ChatRepository(
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao,
    private val attachmentDao: AttachmentDao? = null
) : MessageStore {

    fun observeAttachments(sessionId: String): Flow<List<AttachmentDomainModel>> =
        (attachmentDao?.observeForSession(sessionId) ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .map { list -> list.map { it.toDomain() } }

    suspend fun addAttachment(
        sessionId: String, messageId: String, kind: AttachmentKind, name: String, mime: String, path: String, sizeBytes: Long
    ) {
        attachmentDao?.insert(
            AttachmentEntity(
                sessionId = sessionId, messageId = messageId, kind = kind.name, name = name, mime = mime,
                path = path, sizeBytes = sizeBytes
            )
        )
    }

    private fun AttachmentEntity.toDomain() = AttachmentDomainModel(
        id, sessionId, messageId,
        AttachmentKind.values().firstOrNull { it.name == kind } ?: AttachmentKind.FILE,
        name, mime, path, sizeBytes
    )


    fun observeAllSessions(): Flow<List<ChatSessionDomainModel>> =
        sessionDao.observeAllSessions().map { list -> list.map { it.toDomain() } }

    fun observeSessionById(sessionId: String): Flow<ChatSessionDomainModel?> =
        sessionDao.observeSessionById(sessionId).map { it?.toDomain() }

    fun observeMessagesForSession(sessionId: String): Flow<List<ChatMessageDomainModel>> =
        messageDao.observeMessagesForSession(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun getMessages(sessionId: String): List<ChatMessageDomainModel> =
        messageDao.getMessagesForSession(sessionId).map { it.toDomain() }

    suspend fun getSession(sessionId: String): ChatSessionDomainModel? =
        sessionDao.getSessionById(sessionId)?.toDomain()

    suspend fun createNewSession(title: String = DEFAULT_TITLE, modelPath: String? = null): ChatSessionDomainModel {
        val now = System.currentTimeMillis()
        val entity = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            modelPath = modelPath
        )
        sessionDao.insertSession(entity)
        return entity.toDomain()
    }

    suspend fun updateSessionTitle(sessionId: String, title: String) {
        sessionDao.updateSessionTitle(sessionId, title)
    }

    /** Persists one message, bumps the session's updatedAt, returns the new message id. */
    override suspend fun addMessage(sessionId: String, message: NewMessage): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        messageDao.insertMessage(
            ChatMessageEntity(
                id = id,
                sessionId = sessionId,
                role = message.role,
                content = message.content,
                timestamp = now,
                reasoning = message.reasoning,
                tokensPerSecond = message.stats?.tokensPerSecond?.takeIf { it > 0.0 },
                prefillMs = message.stats?.let { (it.prefillSeconds * 1000.0).toLong() },
                generatedTokens = message.stats?.nGenerated,
                cacheHitPct = message.stats?.cacheHitPct?.takeIf { it >= 0.0 },
                note = message.note
            )
        )
        sessionDao.touchSession(sessionId, now)
        return id
    }

    suspend fun deleteSession(sessionId: String) {
        // chat_messages.session_id has ON DELETE CASCADE; deleting the messages first is harmless.
        messageDao.deleteMessagesForSession(sessionId)
        sessionDao.deleteSessionById(sessionId)
    }

    suspend fun clearAllSessions() {
        sessionDao.deleteAllSessions()
    }

    companion object {
        const val DEFAULT_TITLE = "New Chat"
    }

    private fun ChatSessionEntity.toDomain() =
        ChatSessionDomainModel(id, title, createdAt, updatedAt, modelPath)

    private fun ChatMessageEntity.toDomain() = ChatMessageDomainModel(
        id = id, sessionId = sessionId, role = role, content = content, timestamp = timestamp,
        reasoning = reasoning, tokensPerSecond = tokensPerSecond, prefillMs = prefillMs,
        generatedTokens = generatedTokens, cacheHitPct = cacheHitPct, note = note
    )
}
