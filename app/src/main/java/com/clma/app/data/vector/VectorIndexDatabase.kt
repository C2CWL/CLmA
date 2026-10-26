package com.clma.app.data.vector

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.clma.app.data.db.SQLiteConfiguration

/**
 * The vector index, in its own database file.
 *
 * Separate from `rikka_hub.db` for the same reason the usage ledger is: the index is a **derived
 * cache** - every row in it can be recomputed from a document plus a model - and the main
 * database's upgrade path is load-bearing user data guarded by `ImportedDatabaseReconciler`'s
 * pinned identity hash and hand-written DDL. Adding a cache to that path buys nothing and risks
 * exactly the class of bug (#105) that reconciler exists to prevent.
 *
 * The separation also makes the lifecycle honest: forgetting the index is `delete the file`, and
 * a schema change is "bump [VERSION] and rebuild", because nothing here is user-authored.
 * `exportSchema = false` for the same reason - there is no migration contract to publish.
 *
 * A consequence worth stating: SQL cannot join across the two files, so anything that needs both
 * a chunk and its document joins in memory. That is acceptable because the join is always
 * one-directional (a hit names a document; the document is then fetched by key).
 */
@Database(
    entities = [VectorChunkEntity::class],
    version = VectorIndexDatabase.VERSION,
    exportSchema = false,
)
abstract class VectorIndexDatabase : RoomDatabase() {

    abstract fun vectorChunkDao(): VectorChunkDao

    companion object {
        /**
         * Bump only together with a shape change AND a drop-and-rebuild: nothing in this file is
         * authored by the user, so a destructive migration is the correct answer, not a
         * hand-written one.
         */
        const val VERSION = 1

        const val DATABASE_NAME = "vector_index.db"
    }
}

internal object VectorIndexDatabaseFactory {
    fun create(context: Context, name: String = VectorIndexDatabase.DATABASE_NAME): VectorIndexDatabase =
        Room.databaseBuilder(context, VectorIndexDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()
}
