package com.bigmoe.onedge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bigmoe.onedge.data.local.entity.ArtifactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(artifact: ArtifactEntity)

    @Query("SELECT * FROM artifacts WHERE session_id = :sessionId ORDER BY updated_at DESC")
    fun observeForSession(sessionId: String): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE session_id = :sessionId AND path = :path LIMIT 1")
    suspend fun findByPath(sessionId: String, path: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ArtifactEntity?

    @Query("UPDATE artifacts SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("UPDATE artifacts SET path = :newPath, title = :newTitle, updated_at = :updatedAt WHERE path = :oldPath")
    suspend fun renamePath(oldPath: String, newPath: String, newTitle: String, updatedAt: Long)

    @Query("UPDATE artifacts SET updated_at = :updatedAt WHERE path = :path")
    suspend fun touchByPath(path: String, updatedAt: Long)

    @Query("SELECT * FROM artifacts WHERE path = :path OR path LIKE :prefix")
    suspend fun findByPathOrPrefix(path: String, prefix: String): List<ArtifactEntity>

    @Query("UPDATE artifacts SET path = :newPath, title = :newTitle, language = :language, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePath(id: String, newPath: String, newTitle: String, language: String, updatedAt: Long)

    @Query("DELETE FROM artifacts WHERE path = :path OR path LIKE :prefix")
    suspend fun deleteByPathOrPrefix(path: String, prefix: String)

    @Query("DELETE FROM artifacts WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM artifacts WHERE id = :id")
    suspend fun deleteById(id: String)
}
