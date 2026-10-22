package com.clma.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupNamingTest {

    @Test
    fun `new backups go to the CLmA folder`() {
        assertEquals("CLmA_backups/backup_20261022_1.zip", BackupNaming.s3Key("backup_20261022_1.zip"))
        assertEquals("CLmA_backup_20261022_114300.zip", BackupNaming.localExportFileName("20261022_114300"))
    }

    @Test
    fun `S3 listing accepts both the new CLmA folder and the old rikkahub folder`() {
        assertTrue(BackupNaming.isS3BackupKey("CLmA_backups/backup_20261022_1.zip"))
        assertTrue(BackupNaming.isS3BackupKey("rikkahub_backups/backup_20250101_1.zip"))
    }

    @Test
    fun `S3 listing ignores non-backup keys`() {
        assertFalse(BackupNaming.isS3BackupKey("CLmA_backups/notes.txt"))
        assertFalse(BackupNaming.isS3BackupKey("rikkahub_backups/backup_x.tar"))
        assertFalse(BackupNaming.isS3BackupKey("other/backup_20261022_1.zip"))
        assertFalse(BackupNaming.isS3BackupKey("backup_20261022_1.zip"))
    }

    @Test
    fun `cloud backup file names are matched by the backup_ prefix`() {
        assertTrue(BackupNaming.isBackupFileName("backup_20261022_1.zip"))
        assertFalse(BackupNaming.isBackupFileName("CLmA_backup_20261022.zip"))
        assertFalse(BackupNaming.isBackupFileName("backup_20261022_1.txt"))
    }
}
