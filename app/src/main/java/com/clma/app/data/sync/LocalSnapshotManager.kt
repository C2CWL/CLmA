package com.clma.app.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.clma.app.data.datastore.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No worker, cloud upload or permanent timer: checked only after a delayed foreground entry. */
class LocalSnapshotManager(
    private val context: Context,
    private val backupManager: BackupManager,
    private val settingsStore: SettingsStore,
) {
    private val root = File(context.noBackupFilesDir, "local-snapshots")
    private val store = LocalSnapshotStore(root)
    private val mutex = Mutex()

    suspend fun config(): LocalSnapshotConfig = withContext(Dispatchers.IO) {
        mutex.withLock { store.readConfig() }
    }

    suspend fun updateConfig(transform: (LocalSnapshotConfig) -> LocalSnapshotConfig) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val config = transform(store.readConfig())
            require(config.intervalHours in listOf(12, 24, 72, 168))
            require(config.keepCount in 1..10)
            require(config.maxMiB in listOf(128, 256, 512, 1024))
            store.writeConfig(config)
            // Retention is applied after a verified replacement, not while editing settings.
        }
    }

    suspend fun list(): List<LocalSnapshot> = withContext(Dispatchers.IO) {
        mutex.withLock { store.list() }
    }

    suspend fun checkDue() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val config = store.readConfig()
                val now = System.currentTimeMillis()
                if (!config.isDue(now) || settingsStore.settingsFlowRaw.first().init || restorePending()) return@withLock
                // Stamp before doing work, so process death / cancellation also backs off.
                val attempt = config.copy(lastAttempt = now, failures = (config.failures + 1).coerceAtMost(32))
                store.writeConfig(attempt)
                create(AUTO_ITEMS, rollback = false, config = attempt)
                store.writeConfig(attempt.copy(lastSuccess = System.currentTimeMillis(), failures = 0))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No periodic toast and no payload/credential logging; status is shown in settings.
                Log.w("LocalSnapshots", "Automatic local snapshot failed; retry is deferred")
            }
        }
    }

    /** Failure aborts staging; never silently proceed without the safety copy. */
    suspend fun beforeRestore(items: Collection<BackupItem>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            create(BackupItem.normalize(items), rollback = true, config = store.readConfig())
        }
    }

    suspend fun restore(snapshot: LocalSnapshot) {
        val archive = withContext(Dispatchers.IO) {
            mutex.withLock {
                require(store.list().contains(snapshot)) { "Local snapshot is no longer available" }
                val coroutine = currentCoroutineContext()
                store.verify(snapshot) { coroutine.ensureActive() }
                // Pin the selected archive: creating the safety copy may prune its original.
                val copy = File.createTempFile("local-restore-", ".zip", context.cacheDir)
                try {
                    store.archive(snapshot).copyTo(copy, overwrite = true)
                    copy
                } catch (e: Throwable) {
                    copy.delete()
                    throw e
                }
            }
        }
        try {
            val items = snapshot.items.map { BackupItem.valueOf(it) }
            val password = if (BackupItem.SECRETS in items) withContext(Dispatchers.IO) {
                mutex.withLock { localPassword() }
            } else ""
            backupManager.stageRestore(archive, items, password)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { archive.delete() }
        }
    }

    private suspend fun create(items: Collection<BackupItem>, rollback: Boolean, config: LocalSnapshotConfig) {
        val password = if (BackupItem.SECRETS in items) localPassword() else ""
        val archive = backupManager.createBackup(items, password)
        try {
            val coroutine = currentCoroutineContext()
            val published = store.publish(archive, items.map { it.name }.toSet(), rollback, config, System.currentTimeMillis()) {
                coroutine.ensureActive()
            }
            store.prune(config, published)
        } finally {
            archive.delete()
        }
    }

    private fun restorePending() = File(context.noBackupFilesDir, "backup-restore/pending").exists()

    /** Only the rollback secrets payload needs a password; the wrapping key never leaves Keystore. */
    private fun localPassword(): String {
        val alias = "clma_local_snapshot_password"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
        val file = File(root, "password.enc")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (file.exists()) {
            val bytes = file.readBytes()
            require(bytes.size >= 12 + 16) { "Local rollback key is damaged" }
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
        val password = SecureRandom().generateSeed(32).joinToString("") { "%02x".format(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(root.isDirectory || root.mkdirs()) { "Cannot create local snapshot directory" }
        val temp = File(root, "password.tmp")
        FileOutputStream(temp).use {
            it.write(cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
            it.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE)
        return password
    }

    companion object {
        // No large attachments/workspaces, external config stores or vaults without explicit export.
        val AUTO_ITEMS = listOf(BackupItem.DATABASE, BackupItem.SKILLS, BackupItem.ASSISTANT_MEMORY)
    }
}
