package com.clma.app.data.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_34_35_Test {
    @Test
    fun migrate34To35_preservesParentLinksAndExistingDelegatedIdentity() {
        // In-memory SQLite avoids depending on the unexported v34 Room schema.
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ).name(null).callback(object : SupportSQLiteOpenHelper.Callback(34) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `ConversationEntity` (`id` TEXT NOT NULL PRIMARY KEY, " +
                        "`parent_conversation_id` TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL("INSERT INTO `ConversationEntity` VALUES ('peer', 'parent')")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                Migration_34_35.migrate(db)
            }
        }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val db = helper.writableDatabase
            Migration_34_35.migrate(db)
            db.query("SELECT parent_conversation_id, is_delegated_conversation FROM ConversationEntity").use {
                assertTrue(it.moveToFirst())
                assertEquals("parent", it.getString(0))
                // Parent linkage alone must not classify a peer as delegated.
                assertEquals(0, it.getInt(1))
            }
            db.execSQL("UPDATE ConversationEntity SET is_delegated_conversation = 1")
            Migration_34_35.migrate(db)
            db.query("SELECT is_delegated_conversation FROM ConversationEntity").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            db.query("SELECT * FROM ConversationEntity LIMIT 0").use {
                assertEquals(1, it.columnNames.count { name -> name == "is_delegated_conversation" })
            }
        }
    }
}
