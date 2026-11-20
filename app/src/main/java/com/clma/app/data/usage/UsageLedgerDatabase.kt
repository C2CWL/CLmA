package com.clma.app.data.usage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.clma.app.data.db.SQLiteConfiguration

/** Kept separate from chat data so pruning call details can never touch messages. */
@Database(
    entities = [UsageRecordEntity::class, LifetimeUsage::class],
    version = 2,
    exportSchema = false,
)
abstract class UsageLedgerDatabase : RoomDatabase() {
    abstract fun usageRecordDao(): UsageRecordDao
}

internal object UsageLedgerDatabaseFactory {
    /** Sits beside the main database in the app databases directory. */
    const val DATABASE_NAME = "usage_ledger.db"

    private val migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE lifetime_usage (
                    id INTEGER NOT NULL PRIMARY KEY,
                    input_tokens INTEGER NOT NULL,
                    output_tokens INTEGER NOT NULL,
                    cached_tokens INTEGER NOT NULL,
                    legacy_baselined INTEGER NOT NULL
                )"""
            )
            db.execSQL(
                """INSERT INTO lifetime_usage
                    SELECT 1, COALESCE(SUM(MAX(input_tokens, 0)), 0),
                        COALESCE(SUM(MAX(output_tokens, 0)), 0),
                        COALESCE(SUM(MAX(cached_tokens, 0)), 0), 0
                    FROM usage_records"""
            )
        }
    }

    fun create(context: Context, name: String = DATABASE_NAME): UsageLedgerDatabase =
        Room.databaseBuilder(context, UsageLedgerDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .addMigrations(migration1To2)
            .build()
}
