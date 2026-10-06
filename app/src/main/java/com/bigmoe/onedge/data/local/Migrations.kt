package com.bigmoe.onedge.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN model_names_json TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN generation_state TEXT NOT NULL DEFAULT 'IDLE'")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN generation_started_at INTEGER")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN generation_elapsed_ms INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN generation_tokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN context_used INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN context_total INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN generation_note TEXT")
    }
}


val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN generation_elapsed_ms INTEGER")
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN context_used INTEGER")
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN context_total INTEGER")
    }
}
