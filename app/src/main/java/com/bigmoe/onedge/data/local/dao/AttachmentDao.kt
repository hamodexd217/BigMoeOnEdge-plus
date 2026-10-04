package com.bigmoe.onedge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bigmoe.onedge.data.local.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE session_id = :sessionId ORDER BY created_at ASC, rowid ASC")
    fun observeForSession(sessionId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE session_id = :sessionId ORDER BY created_at ASC, rowid ASC")
    suspend fun getForSession(sessionId: String): List<AttachmentEntity>
}
