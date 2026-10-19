package com.bigmoe.onedge.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.bigmoe.onedge.data.local.converter.Converters
import com.bigmoe.onedge.data.local.dao.ArtifactDao
import com.bigmoe.onedge.data.local.dao.AttachmentDao
import com.bigmoe.onedge.data.local.dao.ChatMessageDao
import com.bigmoe.onedge.data.local.dao.ChatSessionDao
import com.bigmoe.onedge.data.local.entity.ArtifactEntity
import com.bigmoe.onedge.data.local.entity.AttachmentEntity
import com.bigmoe.onedge.data.local.entity.ChatMessageEntity
import com.bigmoe.onedge.data.local.entity.ChatSessionEntity

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        AttachmentEntity::class,
        ArtifactEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun artifactDao(): ArtifactDao
}
