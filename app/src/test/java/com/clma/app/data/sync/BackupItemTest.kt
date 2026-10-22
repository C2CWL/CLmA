package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupItemTest {

    @Test
    fun `selectable never exposes the legacy umbrella`() {
        assertFalse(BackupItem.FILES in BackupItem.selectable)
        assertTrue(BackupItem.DATABASE in BackupItem.selectable)
        // Every other value is pickable.
        assertEquals(BackupItem.entries.size - 1, BackupItem.selectable.size)
    }

    @Test
    fun `legacy files selection expands to every file item but not config`() {
        val normalized = BackupItem.normalize(listOf(BackupItem.DATABASE, BackupItem.FILES))

        assertTrue(BackupItem.DATABASE in normalized)
        assertFalse(BackupItem.FILES in normalized)
        listOf(
            BackupItem.SKILLS,
            BackupItem.UPLOAD,
            BackupItem.IMAGES,
            BackupItem.VIDEOS,
            BackupItem.FONTS,
            BackupItem.WORKSPACES,
            BackupItem.TOOL_OUTPUTS,
        ).forEach { assertTrue("expected $it", it in normalized) }
        // CONFIG is newer than the umbrella and must not be silently enabled for old settings.
        assertFalse(BackupItem.CONFIG in normalized)
    }

    @Test
    fun `normalize keeps picker order and drops duplicates`() {
        val normalized = BackupItem.normalize(
            listOf(BackupItem.CONFIG, BackupItem.SKILLS, BackupItem.SKILLS, BackupItem.DATABASE)
        )
        assertEquals(listOf(BackupItem.DATABASE, BackupItem.SKILLS, BackupItem.CONFIG), normalized)
    }

    @Test
    fun `normalize is a no-op for already-granular selections`() {
        val items = listOf(BackupItem.DATABASE, BackupItem.SKILLS, BackupItem.CONFIG)
        assertEquals(items, BackupItem.normalize(items))
    }

    @Test
    fun `normalize of empty stays empty`() {
        assertTrue(BackupItem.normalize(emptyList()).isEmpty())
    }
}
