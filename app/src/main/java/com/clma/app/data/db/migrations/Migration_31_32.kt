package com.clma.app.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_31_32"

/**
 * v31 → v32: fold sub-agent chats under a parent (`parent_conversation_id`).
 *
 * Hand-written instead of [androidx.room.AutoMigration]: schema `32.json` was never committed
 * after this column landed on main, so Room cannot generate 31→32 (and our later 32→33 step
 * would then also be blocked). Idempotent via PRAGMA table_info so a restored backup that
 * already carries the column does not crash-loop.
 */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 31 to 32 (ConversationEntity.parent_conversation_id)")
        db.beginTransaction()
        try {
            if (!hasColumn(db, "ConversationEntity", "parent_conversation_id")) {
                db.execSQL(
                    "ALTER TABLE `ConversationEntity` ADD COLUMN `parent_conversation_id` TEXT NOT NULL DEFAULT ''"
                )
            }
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 31 to 32 success")
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
