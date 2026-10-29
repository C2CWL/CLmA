package com.clma.app.data.datastore.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** V8: WORKSPACES selections pick up ASSISTANT_MEMORY once after the backup-item split. */
class BackupItemsSplitMigrationTest {

    @Test
    fun `workspaces selection gains assistant memory`() {
        val input = """{"url":"https://dav.example","items":["DATABASE","WORKSPACES","CONFIG"]}"""
        val out = migrateBackupConfigItemsJson(input)
        assertTrue(out.contains("\"ASSISTANT_MEMORY\""))
        assertTrue(out.contains("\"WORKSPACES\""))
        assertTrue(out.contains("\"DATABASE\""))
        assertTrue(out.contains("https://dav.example"))
    }

    @Test
    fun `already-split selection is left alone`() {
        val input = """{"items":["WORKSPACES","ASSISTANT_MEMORY"]}"""
        assertSame(input, migrateBackupConfigItemsJson(input))
    }

    @Test
    fun `selection without workspaces does not add memory`() {
        val input = """{"items":["DATABASE","SKILLS"]}"""
        assertSame(input, migrateBackupConfigItemsJson(input))
    }

    @Test
    fun `invalid json passes through`() {
        assertSame("not json", migrateBackupConfigItemsJson("not json"))
        assertSame("[]", migrateBackupConfigItemsJson("[]"))
    }

    @Test
    fun `settings json migrator rewrites nested webdav and s3 configs`() {
        val input =
            """{"webDavConfig":{"items":["WORKSPACES"]},"s3Config":{"items":["DATABASE","WORKSPACES"]},"themeId":"x"}"""
        val out = SettingsJsonMigrator.migrate(input)
        assertTrue(out.contains("ASSISTANT_MEMORY"))
        assertEquals(2, Regex("ASSISTANT_MEMORY").findAll(out).count())
        assertTrue(out.contains("themeId"))
    }
}
