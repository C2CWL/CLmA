package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupLayoutTest {

    @Test
    fun `every subtree folder round-trips through itemForEntry`() {
        for ((item, dir) in BackupLayout.subtreeFolders) {
            assertEquals(item, BackupLayout.itemForEntry("$dir/file.bin"))
            assertEquals(item, BackupLayout.itemForEntry("$dir/nested/file.bin"))
        }
    }

    @Test
    fun `workspace entries map only into the files area`() {
        assertEquals(BackupItem.WORKSPACES, BackupLayout.itemForEntry("workspaces/abc/files/a.txt"))
        assertEquals(BackupItem.WORKSPACES, BackupLayout.itemForEntry("workspaces/abc/files/deep/a.txt"))
        assertEquals(BackupItem.WORKSPACES, BackupLayout.itemForEntry("workspace/learnings/ERRORS.md"))
        // The proot rootfs and scratch dirs are never archived, so they must not restore either.
        assertNull(BackupLayout.itemForEntry("workspaces/abc/linux/bin/sh"))
        assertNull(BackupLayout.itemForEntry("workspaces/abc/tmp/x"))
        assertNull(BackupLayout.itemForEntry("workspaces/abc"))
    }

    @Test
    fun `config entries are limited to the allowlisted preference stores`() {
        for (file in BackupLayout.configStoreFiles) {
            assertEquals(BackupItem.CONFIG, BackupLayout.itemForEntry("datastore/$file"))
        }
        assertNull(BackupLayout.itemForEntry("datastore/settings.preferences_pb"))
        assertNull(BackupLayout.itemForEntry("datastore/sub/telegram_bot.preferences_pb"))
    }

    @Test
    fun `non-attachment and unsafe entries are rejected`() {
        assertNull(BackupLayout.itemForEntry("settings.json"))
        assertNull(BackupLayout.itemForEntry("database/rikka_hub"))
        assertNull(BackupLayout.itemForEntry("unknown/file"))
        assertNull(BackupLayout.itemForEntry("upload/../x"))
        assertNull(BackupLayout.itemForEntry("skills/./x"))
        assertNull(BackupLayout.itemForEntry("upload/"))
        assertNull(BackupLayout.itemForEntry("/upload/x"))
        assertNull(BackupLayout.itemForEntry("upload"))
    }
}
