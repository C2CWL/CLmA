package com.clma.app.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Explicit identity: parent_conversation_id is also used by peer chats. */
val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val hasColumn = db.query("PRAGMA table_info(`ConversationEntity`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "is_delegated_conversation") found = true
            }
            found
        }
        if (!hasColumn) {
            db.execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `is_delegated_conversation` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}
