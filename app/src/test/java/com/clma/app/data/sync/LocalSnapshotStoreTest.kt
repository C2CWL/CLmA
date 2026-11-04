package com.clma.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(
        name: String = "settings.json",
        stored: Boolean = false,
        bytes: ByteArray = "snapshot-test-content".toByteArray(),
    ): File {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            val entry = ZipEntry(name)
            if (stored) {
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
            }
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
        return file
    }

    private fun publish(store: LocalSnapshotStore, now: Long, rollback: Boolean = false) = store.publish(
        archive(), setOf("DATABASE"), rollback, LocalSnapshotConfig(), now,
    )

    @Test fun defaultIsConservativeAndConfigurationRoundTrips() {
        val store = LocalSnapshotStore(temporary.newFolder())
        assertEquals(LocalSnapshotConfig(), store.readConfig())
        assertFalse(store.readConfig().enabled)
        val config = LocalSnapshotConfig(true, 72, 5, 512, 100, 200, 3)
        store.writeConfig(config)
        assertEquals(config, store.readConfig())
        store.writeConfig(config.copy(enabled = false))
        assertEquals(config.copy(enabled = false), store.readConfig())
    }

    @Test fun publishedSnapshotSurvivesReopeningAndVerifies() {
        val root = temporary.newFolder()
        val store = LocalSnapshotStore(root)
        val snapshot = publish(store, 100)
        assertEquals(listOf(snapshot), LocalSnapshotStore(root).list())
        store.verify(snapshot)
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("preparing-") })
    }

    @Test fun changedArchiveFailsHashVerification() {
        val store = LocalSnapshotStore(temporary.newFolder())
        val snapshot = publish(store, 100)
        RandomAccessFile(store.archive(snapshot), "rw").use {
            it.seek(0)
            it.writeByte(0)
        }
        assertThrows(IllegalArgumentException::class.java) { store.verify(snapshot) }
    }

    @Test fun missingSettingsDoesNotPublishOrRemovePreviousSnapshot() {
        val root = temporary.newFolder()
        val store = LocalSnapshotStore(root)
        val previous = publish(store, 100)
        assertThrows(IllegalArgumentException::class.java) {
            store.publish(archive("other.txt"), emptySet(), false, LocalSnapshotConfig(), 200)
        }
        assertEquals(listOf(previous), store.list())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("preparing-") })
        store.verify(previous)
    }

    @Test fun zipCrcCorruptionDoesNotPublish() {
        val file = archive(stored = true)
        // Corrupt the stored payload only, leaving the central-directory CRC intact.
        RandomAccessFile(file, "rw").use {
            val offset = 30L + "settings.json".toByteArray().size
            it.seek(offset)
            it.writeByte(0)
        }
        val store = LocalSnapshotStore(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) {
            store.publish(file, emptySet(), false, LocalSnapshotConfig(), 100)
        }
        assertTrue(store.list().isEmpty())
    }

    @Test fun cancellationLeavesNoVisibleOrUnfinishedSnapshot() {
        val root = temporary.newFolder()
        val store = LocalSnapshotStore(root)
        assertThrows(IllegalStateException::class.java) {
            store.publish(archive(), emptySet(), false, LocalSnapshotConfig(), 100) { error("cancelled") }
        }
        assertTrue(store.list().isEmpty())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("preparing-") })
    }

    @Test fun traversalEntryIsRejected() {
        val store = LocalSnapshotStore(temporary.newFolder())
        val file = archive()
        // A valid settings entry is present, so rejection must concern the traversal entry.
        val source = temporary.newFile()
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../escape"))
            zip.write(file.readBytes())
            zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.publish(source, emptySet(), false, LocalSnapshotConfig(), 100)
        }
        assertTrue(store.list().isEmpty())
    }

    @Test fun oversizeArchivePreservesExistingSnapshot() {
        val store = LocalSnapshotStore(temporary.newFolder())
        val previous = publish(store, 100)
        assertThrows(IllegalArgumentException::class.java) {
            store.publish(archive(), emptySet(), false, LocalSnapshotConfig(maxMiB = 0), 200)
        }
        assertEquals(listOf(previous), store.list())
        store.verify(previous)
    }

    @Test fun retentionKeepsSeparateAutomaticAndRollbackPools() {
        val root = temporary.newFolder()
        val store = LocalSnapshotStore(root)
        publish(store, 100)
        val automatic = publish(store, 200)
        publish(store, 300, rollback = true)
        val rollback = publish(store, 400, rollback = true)
        File(root, "preparing-interrupted").mkdirs()
        store.prune(LocalSnapshotConfig(keepCount = 1))
        assertEquals(listOf(rollback, automatic), store.list())
        assertFalse(File(root, "preparing-interrupted").exists())
        store.verify(automatic)
        store.verify(rollback)
    }

    @Test fun retentionEnforcesCombinedAutomaticByteBudget() {
        val store = LocalSnapshotStore(temporary.newFolder())
        val source = archive(stored = true, bytes = ByteArray(700_000) { 42 })
        store.publish(source, emptySet(), false, LocalSnapshotConfig(), 100)
        val latest = store.publish(source, emptySet(), false, LocalSnapshotConfig(), 200)
        // A small store-only budget keeps this real-archive test lightweight.
        store.prune(LocalSnapshotConfig(maxMiB = 1))
        assertEquals(listOf(latest), store.list())
        store.verify(latest)
    }

    @Test fun clockCorrectionDoesNotPruneNewSafetyCopy() {
        val store = LocalSnapshotStore(temporary.newFolder())
        publish(store, 200, rollback = true)
        val replacement = publish(store, 100, rollback = true)
        store.prune(LocalSnapshotConfig(), replacement)
        assertEquals(listOf(replacement), store.list())
        store.verify(replacement)
    }

    @Test fun disabledAndNotYetDueDoNotRun() {
        val hour = LocalSnapshotConfig.HOUR
        assertFalse(LocalSnapshotConfig().isDue(100 * hour))
        assertTrue(LocalSnapshotConfig(enabled = true).isDue(hour))
        val config = LocalSnapshotConfig(enabled = true, lastSuccess = hour, lastAttempt = hour)
        assertFalse(config.isDue(24 * hour))
        assertTrue(config.isDue(25 * hour))
        assertFalse(config.isDue(0))
    }

    @Test fun retriesBackOffAndCapAtTwentyFourHours() {
        val hour = LocalSnapshotConfig.HOUR
        for (failures in 1..8) {
            val config = LocalSnapshotConfig(enabled = true, lastAttempt = hour, failures = failures)
            val delay = (1L shl (failures - 1)).coerceAtMost(24) * hour
            assertFalse(config.isDue(hour + delay - 1))
            assertTrue(config.isDue(hour + delay))
        }
    }
}
