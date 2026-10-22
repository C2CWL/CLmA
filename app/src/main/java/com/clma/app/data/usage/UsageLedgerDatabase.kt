package me.rerere.rikkahub.data.usage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration

/**
 * P2-11c — the usage ledger database.
 *
 * It is deliberately a **separate** database file rather than one more table inside
 * me.rerere.rikkahub.data.db.AppDatabase:
 *
 *  - The ledger is pure telemetry. Keeping it out of `rikka_hub.db` means a retention sweep
 *    can never touch a user message, and the fork restore path (ImportedDatabaseReconciler,
 *    which pins the Room identity hash and a fork-only table DDL list) stays untouched —
 *    that path exists because a bad migration crashed the app on first launch (issue #105),
 *    so adding to it is not something a telemetry table should risk.
 *  - It has no foreign keys and never joins: every lookup is by id, so nothing is lost.
 *
 * The trade-off is that SQL cannot join across the two files; the stats and export code
 * joins in memory instead. The version stays at 1: with a single append-only table there is
 * no migration chain worth maintaining, and if the shape ever changes the file can simply be
 * dropped and recreated — telemetry is disposable by definition.
 */
@Database(
    entities = [UsageRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class UsageLedgerDatabase : RoomDatabase() {
    abstract fun usageRecordDao(): UsageRecordDao
}

internal object UsageLedgerDatabaseFactory {
    /** Sits beside the main database in the app databases directory. */
    const val DATABASE_NAME = "usage_ledger.db"

    fun create(context: Context, name: String = DATABASE_NAME): UsageLedgerDatabase =
        Room.databaseBuilder(context, UsageLedgerDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()
}
