package com.clma.app.data.sync

import com.clma.app.R
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.clma.app.data.secrets.AgentSecretsStore
import com.clma.app.workflow.secrets.WorkflowSecretsStore
import com.clma.app.skills.js.SkillSecretsStore
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.datastore.migration.SettingsJsonMigrator
import com.clma.app.data.db.AppDatabaseFactory
import com.clma.app.data.db.AppDatabase
import com.clma.app.data.db.ImportedDatabaseReconciler
import com.clma.app.data.db.SQLiteConfiguration
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    /** Estimated (pre-compression) size of each selectable [BackupItem]. Runs on the IO dispatcher. */
    suspend fun estimateItemSizes(): Map<BackupItem, Long> = withContext(Dispatchers.IO) {
        val db = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
        BackupSizeEstimator.estimate(
            filesDir = context.filesDir,
            databaseFiles = listOf(db, File(db.path + "-wal")),
        )
    }

    suspend fun createBackup(items: Collection<BackupItem>, encryptionPassword: String = ""): File = withContext(Dispatchers.IO) {
        val selected = BackupItem.normalize(items).toSet()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.settingsFlowRaw.first()
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(json.encodeToString(settings).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (BackupItem.DATABASE in selected) {
                    val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                    DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                }
                if (BackupItem.CONFIG in selected) {
                    for (name in BackupLayout.sharedPreferenceFiles) {
                        // commit waits for pending apply writes before the XML snapshot is read.
                        check(context.getSharedPreferences(name.removeSuffix(".xml"), Context.MODE_PRIVATE)
                            .edit().commit()) { "Cannot flush backup preferences: $name" }
                        val file = File(context.filesDir.parentFile, "shared_prefs/$name")
                        if (file.isFile) addFile(zip, file, "shared_prefs/$name")
                    }
                    val knownHosts = File(context.filesDir, "known_hosts")
                    if (knownHosts.isFile) addFile(zip, knownHosts, "known_hosts")
                    for (name in BackupLayout.configStoreFiles) {
                        val file = File(context.filesDir, "${BackupLayout.DATASTORE_DIR}/$name")
                        if (!file.isFile) continue
                        currentCoroutineContext().ensureActive()
                        addFile(zip, file, "${BackupLayout.DATASTORE_DIR}/$name")
                    }
                }
                for ((item, folder) in BackupLayout.subtreeFolders) {
                    if (item !in selected) continue
                    val directory = File(context.filesDir, folder)
                    addTree(zip, directory, folder)
                }
                if (BackupItem.WORKSPACES in selected) {
                    addWorkspaces(zip)
                }
                if (BackupItem.SECRETS in selected) {
                    require(encryptionPassword.isNotBlank()) {
                        context.getString(R.string.backup_secrets_password_required)
                    }
                    val agentSecrets = AgentSecretsStore(context).exportAll()
                    val workflowSecrets = WorkflowSecretsStore(context).exportAll()
                    val skillSecrets = SkillSecretsStore(context).exportAll()
                    val payload = SecretsBackupPayload(
                        agentSecrets = agentSecrets,
                        workflowSecrets = workflowSecrets,
                        skillSecrets = skillSecrets,
                    )
                    val encryptedBytes = SecretsBackupCrypto.encrypt(payload, encryptionPassword.toCharArray(), json)
                    val secretsFile = File(staging, BackupLayout.SECRETS_FILE)
                    secretsFile.writeBytes(encryptedBytes)
                    addFile(zip, secretsFile, BackupLayout.SECRETS_FILE)
                }
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Archives every file under [directory] as `<prefix>/<relative path>`. */
    private suspend fun addTree(zip: ZipOutputStream, directory: File, prefix: String) {
        for (file in directory.walkTopDown()) {
            if (!file.isFile) continue
            currentCoroutineContext().ensureActive()
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            PendingRestore.resolveInside(directory, relative)
            addFile(zip, file, "$prefix/$relative")
        }
    }

    /**
     * Workspace data, deliberately excluding the proot rootfs (`workspaces/<root>/linux`, often
     * hundreds of MB and reproducible by reinstalling) and scratch dirs (`tmp`). Two areas are
     * captured: the per-workspace `files/` tree, and the on-device agent's `~`.
     */
    private suspend fun addWorkspaces(zip: ZipOutputStream) {
        val roots = File(context.filesDir, BackupLayout.WORKSPACES_ROOT)
        for (root in roots.listFiles().orEmpty()) {
            if (!root.isDirectory) continue
            val filesDir = File(root, BackupLayout.WORKSPACE_FILES_DIR)
            addTree(
                zip = zip,
                directory = filesDir,
                prefix = "${BackupLayout.WORKSPACES_ROOT}/${root.name}/${BackupLayout.WORKSPACE_FILES_DIR}",
            )
        }
        addTree(
            zip = zip,
            directory = File(context.filesDir, BackupLayout.AGENT_WORKSPACE_DIR),
            prefix = BackupLayout.AGENT_WORKSPACE_DIR,
        )
    }

    suspend fun stageRestore(archive: File, items: Collection<BackupItem>, encryptionPassword: String = "") =
        withContext(Dispatchers.IO) {
            val selected = BackupItem.normalize(items).toSet()
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    val quota = RestoreQuota(availableBytes = { staging.usableSpace })
                    val restoreContext = currentCoroutineContext()
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            quota.countEntry()
                            if (entry.isDirectory) continue
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                DatabaseBackup.ARCHIVE_DATABASE ->
                                    if (BackupItem.DATABASE in selected) stagedDatabase else null

                                DatabaseBackup.WAL ->
                                    if (BackupItem.DATABASE in selected) stagedWal else null

                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                BackupLayout.SECRETS_FILE ->
                                    if (BackupItem.SECRETS in selected) File(staging, BackupLayout.SECRETS_FILE) else null
                                else -> {
                                    val item = BackupLayout.itemForEntry(entry.name)
                                    if (item != null && BackupLayout.acceptsRestoreEntry(item, selected)) {
                                        PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                    } else null
                                }
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    quota.copy(input, output) { restoreContext.ensureActive() }
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // A backup exported from upstream RikkaHub lacks the fork-only tables and
                        // carries upstream's schema version; reconcile the staged copy before Room
                        // opens it so the import neither fails validation nor crashes on first launch.
                        ImportedDatabaseReconciler.reconcileDatabaseFile(stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val secretsEncFile = File(staging, BackupLayout.SECRETS_FILE)
                    if (secretsEncFile.exists() && BackupItem.SECRETS in selected) {
                        require(encryptionPassword.isNotBlank()) {
                            context.getString(R.string.backup_secrets_enter_password)
                        }
                        val payload = SecretsBackupCrypto.decrypt(secretsEncFile.readBytes(), encryptionPassword.toCharArray(), json)
                        val stagedSecretsFile = File(staging, "staged_secrets.json")
                        PendingRestore.writeDurably(stagedSecretsFile, json.encodeToString(SecretsBackupPayload.serializer(), payload))
                        secretsEncFile.delete()
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val settings = json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(settingsFile.readText()))
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, json.encodeToString(settings))
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).installRestore(
                restoreSettings = { settingsJson ->
                    val settings = json.decodeFromString<Settings>(settingsJson)
                    SettingsStore.restoreBeforeInitialization(
                        context = context,
                        settings = LocalUriRemap.apply(settings, context.filesDir),
                    )
                },
                restoreSecrets = { secretsJson ->
                    val payload = json.decodeFromString(SecretsBackupPayload.serializer(), secretsJson)
                    if (payload.agentSecrets.isNotEmpty()) {
                        AgentSecretsStore(context).importAll(payload.agentSecrets)
                    }
                    if (payload.workflowSecrets.isNotEmpty()) {
                        WorkflowSecretsStore(context).importAll(payload.workflowSecrets)
                    }
                    if (payload.skillSecrets.isNotEmpty()) {
                        SkillSecretsStore(context).importAll(payload.skillSecrets)
                    }
                }
            )
        }
    }
}