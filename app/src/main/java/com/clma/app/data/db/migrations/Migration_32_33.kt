package com.clma.app.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_32_33"

/**
 * v32 → v33: per-conversation Agent/聊天 mode switch.
 *
 * Adds `agent_mode_enabled` to ConversationEntity (INTEGER NOT NULL DEFAULT 1). Default 1
 * keeps existing chats in Agent mode so behavior is unchanged. Hand-written rather than an
 * AutoMigration because schema 32.json was never exported after `parent_conversation_id`
 * landed; Room cannot synthesize 32→33 without that file. Idempotent via PRAGMA table_info
 * so a restored backup that already carries the column does not crash-loop.
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 32 to 33 (ConversationEntity.agent_mode_enabled)")
        db.beginTransaction()
        try {
            if (!hasColumn(db, "ConversationEntity", "agent_mode_enabled")) {
                db.execSQL(
                    "ALTER TABLE `ConversationEntity` ADD COLUMN `agent_mode_enabled` INTEGER NOT NULL DEFAULT 1"
                )
            }
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 32 to 33 success")
        } finally {
            db.endTransaction()
        }
    }
}

/** True if [table] has a column named [column]. */
private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}
