package com.clma.app.data.sync

/**
 * User-visible names for backup files and cloud backup folders.
 *
 * New backups use the CLmA names. The old RikkaHub names stay recognized so backups made by
 * earlier versions (or upstream RikkaHub) still show up in the cloud lists and can be restored.
 */
object BackupNaming {
    /** Cloud folder (S3 key prefix / default WebDAV path) for new backups. */
    const val CLOUD_FOLDER = "CLmA_backups"

    /** Cloud folder used by earlier versions; still listed and restorable. */
    const val LEGACY_CLOUD_FOLDER = "rikkahub_backups"

    /** All cloud folders that may hold backups, newest naming first. */
    val CLOUD_FOLDERS: List<String> = listOf(CLOUD_FOLDER, LEGACY_CLOUD_FOLDER)

    /** Suggested file name for a local "export backup" zip. */
    fun localExportFileName(timestamp: String): String = "CLmA_backup_$timestamp.zip"

    /** A cloud backup file name as written by [BackupManager.createBackup] (`backup_<ts>_<n>.zip`). */
    fun isBackupFileName(name: String): Boolean = name.startsWith("backup_") && name.endsWith(".zip")

    /** S3 object key for a new backup file. */
    fun s3Key(fileName: String): String = "$CLOUD_FOLDER/$fileName"

    /** Whether an S3 key is a backup in the new CLmA folder or the old rikkahub folder. */
    fun isS3BackupKey(key: String): Boolean = CLOUD_FOLDERS.any { folder ->
        key.startsWith("$folder/") && isBackupFileName(key.removePrefix("$folder/"))
    }
}
