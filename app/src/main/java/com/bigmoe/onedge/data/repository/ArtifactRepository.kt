package com.bigmoe.onedge.data.repository

import com.bigmoe.onedge.data.local.dao.ArtifactDao
import com.bigmoe.onedge.data.local.entity.ArtifactEntity
import com.bigmoe.onedge.tools.ArtifactRegistry
import com.bigmoe.onedge.tools.ToolContext
import com.bigmoe.onedge.workspace.FileKinds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ArtifactDomainModel(
    val id: String,
    val sessionId: String,
    val messageId: String,
    val title: String,
    val path: String,
    val language: String,
    val updatedAt: Long
)

/** AI-made files, linked to the chat that created them. The bytes are workspace files, this is the index. */
class ArtifactRepository(private val dao: ArtifactDao) : ArtifactRegistry {

    fun observe(sessionId: String): Flow<List<ArtifactDomainModel>> =
        dao.observeForSession(sessionId).map { list -> list.map { it.toDomain() } }

    /** Adds (or refreshes) an artifact for [path] in the chat; returns its id. */
    suspend fun register(sessionId: String, messageId: String, path: String, title: String? = null, language: String? = null): String {
        val existing = dao.findByPath(sessionId, path)
        val now = System.currentTimeMillis()
        if (existing != null) {
            dao.touch(existing.id, now)
            return existing.id
        }
        val entity = ArtifactEntity(
            sessionId = sessionId,
            messageId = messageId,
            title = title ?: path.substringAfterLast('/'),
            path = path,
            language = language ?: FileKinds.languageFor(path),
            createdAt = now,
            updatedAt = now
        )
        dao.insert(entity)
        return entity.id
    }

    override suspend fun created(ctx: ToolContext, path: String) {
        // Tool calls outside a chat (tests) have no session: nothing to link.
        if (ctx.sessionId.isNotEmpty()) register(ctx.sessionId, ctx.messageId, path)
    }

    override suspend fun folderCreated(ctx: ToolContext, path: String) {
        if (ctx.sessionId.isNotEmpty()) register(ctx.sessionId, ctx.messageId, path, title = path.substringAfterLast('/'), language = FileKinds.FOLDER)
    }

    override suspend fun updated(path: String) {
        dao.touchByPath(path, System.currentTimeMillis())
    }

    override suspend fun renamed(from: String, to: String) {
        val now = System.currentTimeMillis()
        for (a in dao.findByPathOrPrefix(from, "$from/%")) {
            val newPath = if (a.path == from) to else to + a.path.removePrefix(from)
            dao.updatePath(a.id, newPath, newPath.substringAfterLast('/'), if (a.language == FileKinds.FOLDER) FileKinds.FOLDER else FileKinds.languageFor(newPath), now)
        }
    }

    override suspend fun deleted(path: String) {
        dao.deleteByPathOrPrefix(path, "$path/%")
    }

    private fun ArtifactEntity.toDomain() = ArtifactDomainModel(id, sessionId, messageId, title, path, language, updatedAt)
}
