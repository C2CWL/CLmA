package com.clma.app.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.clma.app.data.sync.BackupManager
import com.clma.app.data.sync.BackupNaming
import com.clma.app.data.datastore.WebDavConfig
import com.clma.app.utils.fileSizeToString
import java.io.File
import java.time.Instant

private const val TAG = "WebDavSync"

class WebDavSync(
    private val backupManager: BackupManager,
    private val context: Context,
    private val httpClient: HttpClient,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        // Upload the backup file
        client.put(
            path = file.name,
            file = file,
            contentType = "application/zip"
        ).getOrThrow()

        Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")

        // Clean up temp file
        file.delete()
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val current = client.list().getOrThrow().toBackupItems(folder = null)

        // On the new default folder, also show backups left in the old rikkahub folder so they
        // can still be restored. A missing old folder is not an error.
        val legacy = if (isDefaultFolder(config.path)) {
            getClient(config.copy(path = BackupNaming.LEGACY_CLOUD_FOLDER)).list()
                .getOrNull().orEmpty()
                .toBackupItems(folder = BackupNaming.LEGACY_CLOUD_FOLDER)
        } else {
            emptyList()
        }

        (current + legacy).sortedByDescending { it.lastModified }
    }

    private fun isDefaultFolder(path: String): Boolean =
        path.trim('/') == BackupNaming.CLOUD_FOLDER

    private fun List<WebDavResourceInfo>.toBackupItems(folder: String?): List<WebDavBackupItem> =
        filter { !it.isCollection && BackupNaming.isBackupFileName(it.displayName) }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH,
                    folder = folder,
                )
            }

    /** Client for the folder an item was listed from (the configured one, or the old rikkahub one). */
    private fun clientFor(config: WebDavConfig, item: WebDavBackupItem): WebDavClient =
        getClient(item.folder?.let { config.copy(path = it) } ?: config)

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = clientFor(config, item)
        val backupFile = File.createTempFile("restore-", ".zip", context.cacheDir)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            client.downloadToFile(item.displayName, backupFile).getOrThrow()

            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = clientFor(config, item)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) {
        restoreFromBackupFile(file, config)
    }

    suspend fun prepareBackupFile(config: WebDavConfig): File = backupManager.createBackup(config.items)

    private suspend fun restoreFromBackupFile(backupFile: File, config: WebDavConfig) = backupManager.stageRestore(
        archive = backupFile,
        items = config.items,
    )

}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
    /** Set when the item lives in a different folder than the configured path (the old rikkahub one). */
    val folder: String? = null,
)
