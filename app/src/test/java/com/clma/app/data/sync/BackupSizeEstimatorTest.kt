package com.clma.app.data.sync

import java.nio.file.Files
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupSizeEstimatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(path: String, bytes: Int): File =
        File(temp.root, path).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Test
    fun `additional trees accumulate instead of replacing the first folder`() {
        val root = Files.createTempDirectory("backup-size-extra").toFile()
        try {
            fun file(path: String, content: String) {
                File(root, path).apply { parentFile.mkdirs(); writeText(content) }
            }
            file("upload/a", "abc")
            file("browser-exports/b", "12345")
            file("assistant_memory/id/memory/note.md", "notes")
            file("workspace/a", "ab")
            file("known_hosts", "host")
            val sizes = BackupSizeEstimator.estimate(root, emptyList())
            assertEquals(8L, sizes[BackupItem.UPLOAD])
            assertEquals(2L, sizes[BackupItem.WORKSPACES])
            assertEquals(5L, sizes[BackupItem.ASSISTANT_MEMORY])
            assertEquals(4L, sizes[BackupItem.CONFIG])
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `directory size sums nested files and is zero when missing`() {
        write("files/images/a.png", 100)
        write("files/images/sub/b.png", 50)
        assertEquals(150L, BackupSizeEstimator.directorySize(File(temp.root, "files/images")))
        assertEquals(0L, BackupSizeEstimator.directorySize(File(temp.root, "files/nope")))
    }

    @Test
    fun `estimate maps each item to the folders it backs up`() {
        val filesDir = File(temp.root, "files")
        write("files/images/a.png", 100)
        write("files/videos/v.mp4", 300)
        write("files/datastore/telegram_bot.preferences_pb", 7)
        write("files/datastore/settings.preferences_pb", 1000) // not part of CONFIG
        write("files/workspaces/w1/files/x.txt", 20)
        write("files/workspaces/w1/linux/rootfs.bin", 5000) // excluded from backups
        write("files/workspace/notes.md", 5)
        write("shared_prefs/agent_secrets.xml", 50)
        write("shared_prefs/workflow_secrets.xml", 30)
        write("shared_prefs/skill_secrets.xml", 20)
        val db = write("databases/rikka_hub", 400)
        val wal = File(temp.root, "databases/rikka_hub-wal") // missing is fine

        val sizes = BackupSizeEstimator.estimate(filesDir, listOf(db, wal))

        assertEquals(400L, sizes[BackupItem.DATABASE])
        assertEquals(100L, sizes[BackupItem.IMAGES])
        assertEquals(300L, sizes[BackupItem.VIDEOS])
        assertEquals(7L, sizes[BackupItem.CONFIG])
        assertEquals(25L, sizes[BackupItem.WORKSPACES])
        assertEquals(100L, sizes[BackupItem.SECRETS])
        assertEquals(0L, sizes[BackupItem.SKILLS])
        assertEquals(BackupItem.selectable.toSet(), sizes.keys)
    }

    @Test
    fun `total only counts selected items and expands legacy FILES`() {
        val sizes = mapOf(
            BackupItem.DATABASE to 400L,
            BackupItem.IMAGES to 100L,
            BackupItem.VIDEOS to 300L,
        )
        assertEquals(500L, BackupSizeEstimator.totalFor(sizes, listOf(BackupItem.DATABASE, BackupItem.IMAGES)))
        assertEquals(400L, BackupSizeEstimator.totalFor(sizes, listOf(BackupItem.FILES)))
        assertEquals(0L, BackupSizeEstimator.totalFor(sizes, emptyList()))
    }
}
