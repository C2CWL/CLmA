package me.rerere.rikkahub.data.agentdef

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import me.rerere.rikkahub.data.db.SQLiteConfiguration

/**
 * P2-06 — the expert library database.
 *
 * A **separate** database file rather than one more table inside
 * [me.rerere.rikkahub.data.db.AppDatabase], for the same reason the P2-11c usage ledger is:
 *
 *  - `ImportedDatabaseReconciler` pins `AppDatabase`'s schema version, its Room **identity
 *    hash** and the fork-only table DDL list, and its unit test asserts those constants match
 *    the newest exported schema. Adding an entity moves the identity hash, which only the Room
 *    compiler can produce — so the change cannot be validated locally and costs CI rounds on
 *    a path that already crashed the app once (issue #105).
 *  - Nothing in the ledger of experts needs a foreign key or a join into `rikka_hub`.
 *
 * The cost, recorded so it is not rediscovered: definitions are **not** part of the chat
 * backup import/restore flow, and code that needs both a definition and a conversation joins
 * in memory.
 *
 * Version stays at 1. Unlike the telemetry ledger this file holds **user data**, so a shape
 * change must ship a real `Migration` (or a hand-written backfill) rather than dropping and
 * recreating the file.
 */
@Database(
    entities = [AgentDefinition::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(AgentDefinitionConverters::class)
abstract class AgentDefinitionDatabase : RoomDatabase() {
    abstract fun agentDefinitionDao(): AgentDefinitionDao
}

internal object AgentDefinitionDatabaseFactory {
    /** Sits beside the chat database in the app's databases directory. */
    const val DATABASE_NAME = "agent_definitions.db"

    fun create(context: Context, name: String = DATABASE_NAME): AgentDefinitionDatabase =
        Room.databaseBuilder(context, AgentDefinitionDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()
}
