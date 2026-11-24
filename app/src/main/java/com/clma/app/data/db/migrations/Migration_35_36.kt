package com.clma.app.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_35_36"

/**
 * v35 → v36: the `context_operation_log` table backing the "compression & clear history" page.
 *
 * Hand-written instead of an AutoMigration: 32.json..35.json were never committed (fork
 * migrations 31→35 are all explicit), so Room cannot generate 35→36 from a schema file.
 * The CREATE TABLE below is byte-identical in shape to what Room generates for
 * [com.clma.app.data.db.entity.ContextOperationLogEntity] (no indices, no foreign keys,
 * no default values), so the Room identity hash stays consistent. `IF NOT EXISTS` keeps the
 * step idempotent for databases restored from a backup that already carried the table.
 */
val Migration_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 35 to 36 (context_operation_log)")
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `context_operation_log` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversation_id` TEXT NOT NULL,
                    `conversation_title` TEXT,
                    `operation_type` TEXT NOT NULL,
                    `is_auto` INTEGER NOT NULL,
                    `source_token_estimate` INTEGER NOT NULL,
                    `summary_token_estimate` INTEGER NOT NULL,
                    `message_count` INTEGER NOT NULL,
                    `result` TEXT NOT NULL,
                    `details` TEXT,
                    `created_at` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 35 to 36 success")
        } finally {
            db.endTransaction()
        }
    }
}
