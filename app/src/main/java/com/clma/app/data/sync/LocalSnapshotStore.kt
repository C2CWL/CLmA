package com.clma.app.data.sync

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

data class LocalSnapshot(
    val id: String,
    val createdAt: Long,
    val rollback: Boolean,
    val items: Set<String>,
    val sizeBytes: Long,
    val sha256: String,
)

data class LocalSnapshotConfig(
    val enabled: Boolean = false,
    val intervalHours: Int = 24,
    val keepCount: Int = 3,
    val maxMiB: Int = 256,
    val lastSuccess: Long = 0,
    val lastAttempt: Long = 0,
    val failures: Int = 0,
) {
    val maxBytes: Long get() = maxMiB * 1024L * 1024L

    fun isDue(now: Long): Boolean {
        if (!enabled) return false
        val retryMillis = if (lastAttempt <= lastSuccess) 0L else
            (1L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(24) * HOUR
        // Clock rollback must not trigger a tight retry loop.
        return (lastSuccess == 0L || now - lastSuccess >= intervalHours * HOUR) &&
            (lastAttempt == 0L || now - lastAttempt >= retryMillis)
    }

    companion object {
        const val HOUR = 3_600_000L
    }
}

/** App-private, non-cloud storage. A snapshot becomes visible only after archive + metadata sync. */
internal class LocalSnapshotStore(private val root: File) {
    private val configFile get() = File(root, "config.properties")

    fun readConfig(): LocalSnapshotConfig {
        if (!configFile.exists()) return LocalSnapshotConfig()
        val p = properties(configFile)
        return LocalSnapshotConfig(
            enabled = p.getProperty("enabled") == "true",
            intervalHours = p.getProperty("intervalHours").toInt().also { require(it in listOf(12, 24, 72, 168)) },
            keepCount = p.getProperty("keepCount").toInt().also { require(it in 1..10) },
            maxMiB = p.getProperty("maxMiB").toInt().also { require(it in listOf(128, 256, 512, 1024)) },
            lastSuccess = p.getProperty("lastSuccess").toLong().coerceAtLeast(0),
            lastAttempt = p.getProperty("lastAttempt").toLong().coerceAtLeast(0),
            failures = p.getProperty("failures").toInt().coerceIn(0, 32),
        )
    }

    fun writeConfig(config: LocalSnapshotConfig) {
        val p = Properties().apply {
            setProperty("enabled", config.enabled.toString())
            setProperty("intervalHours", config.intervalHours.toString())
            setProperty("keepCount", config.keepCount.toString())
            setProperty("maxMiB", config.maxMiB.toString())
            setProperty("lastSuccess", config.lastSuccess.toString())
            setProperty("lastAttempt", config.lastAttempt.toString())
            setProperty("failures", config.failures.toString())
        }
        check(root.isDirectory || root.mkdirs()) { "Cannot create local snapshot directory" }
        val temp = File(root, "config.tmp")
        writeProperties(temp, p)
        Files.move(temp.toPath(), configFile.toPath(), ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    fun list(): List<LocalSnapshot> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("snapshot-") }
        .map { directory ->
            val p = properties(File(directory, "manifest.properties"))
            require(p.getProperty("version") == "1") { "Unsupported local snapshot version" }
            LocalSnapshot(
                id = directory.name,
                createdAt = p.getProperty("createdAt").toLong(),
                rollback = p.getProperty("rollback").toBooleanStrict(),
                items = p.getProperty("items").split(',').filter { it.isNotBlank() }.toSet(),
                sizeBytes = p.getProperty("sizeBytes").toLong(),
                sha256 = p.getProperty("sha256"),
            )
        }.sortedByDescending { it.createdAt }

    fun archive(snapshot: LocalSnapshot): File {
        require(snapshot.id.startsWith("snapshot-") && File(snapshot.id).name == snapshot.id)
        return File(root, "${snapshot.id}/backup.zip")
    }

    fun verify(snapshot: LocalSnapshot, checkCancelled: () -> Unit = {}) {
        val file = archive(snapshot)
        require(file.length() == snapshot.sizeBytes && digest(file, checkCancelled) == snapshot.sha256) {
            "Local snapshot checksum mismatch"
        }
        verifyZip(file, checkCancelled)
    }

    fun publish(
        source: File,
        items: Set<String>,
        rollback: Boolean,
        config: LocalSnapshotConfig,
        now: Long,
        checkCancelled: () -> Unit = {},
    ): LocalSnapshot {
        require(source.isFile) { "Snapshot archive is missing" }
        require(source.length() <= config.maxBytes) { "Snapshot exceeds the configured local storage limit" }
        check(root.isDirectory || root.mkdirs()) { "Cannot create local snapshot directory" }
        // Never prune a known-good snapshot to make space for an unverified replacement.
        require(root.usableSpace > source.length() + 32L * 1024 * 1024) { "Not enough space for a local snapshot" }
        val staging = Files.createTempDirectory(root.toPath(), "preparing-").toFile()
        try {
            val archive = File(staging, "backup.zip")
            source.inputStream().use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            verifyZip(archive, checkCancelled)
            val snapshot = LocalSnapshot("snapshot-${UUID.randomUUID()}", now, rollback, items, archive.length(), digest(archive, checkCancelled))
            writeProperties(File(staging, "manifest.properties"), Properties().apply {
                setProperty("version", "1")
                setProperty("createdAt", now.toString())
                setProperty("rollback", rollback.toString())
                setProperty("items", items.sorted().joinToString(","))
                setProperty("sizeBytes", snapshot.sizeBytes.toString())
                setProperty("sha256", snapshot.sha256)
            })
            checkCancelled()
            Files.move(staging.toPath(), File(root, snapshot.id).toPath(), ATOMIC_MOVE)
            return snapshot
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Separate pools: auto snapshots share the budget; the latest restore safety copy has its own. */
    fun prune(config: LocalSnapshotConfig, published: LocalSnapshot? = null) {
        var bytes = 0L
        var count = 0
        var rollbackKept = false
        // A clock correction must not evict the replacement we just verified.
        list().sortedByDescending { it.id == published?.id }.forEach { snapshot ->
            val keep = if (snapshot.rollback) {
                (!rollbackKept).also { rollbackKept = true }
            } else {
                (count < config.keepCount && bytes + snapshot.sizeBytes <= config.maxBytes).also {
                    if (it) {
                        count++
                        bytes += snapshot.sizeBytes
                    }
                }
            }
            if (!keep) check(File(root, snapshot.id).deleteRecursively()) { "Cannot prune local snapshot" }
        }
        root.listFiles().orEmpty().filter { it.name.startsWith("preparing-") }.forEach {
            check(it.deleteRecursively()) { "Cannot clean unfinished local snapshot" }
        }
    }

    private fun properties(file: File) = Properties().apply { file.inputStream().use { load(it) } }

    private fun writeProperties(file: File, p: Properties) {
        FileOutputStream(file).use { output ->
            p.store(output, null)
            output.fd.sync()
        }
    }

    private fun digest(file: File, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyZip(file: File, checkCancelled: () -> Unit) {
        ZipFile(file).use { zip ->
            require(zip.getEntry("settings.json") != null) { "Snapshot settings are missing" }
            val names = mutableSetOf<String>()
            val quota = RestoreQuota(availableBytes = { Long.MAX_VALUE })
            zip.entries().asSequence().forEach { entry ->
                quota.countEntry()
                require(names.add(entry.name)) { "Duplicate snapshot entry" }
                require(!entry.name.startsWith('/') && '\\' !in entry.name &&
                    entry.name.trimEnd('/').split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                    "Invalid snapshot entry path"
                }
                if (!entry.isDirectory) {
                    val crc = CRC32()
                    val output = object : java.io.OutputStream() {
                        override fun write(b: Int) { crc.update(b) }
                        override fun write(b: ByteArray, off: Int, len: Int) { crc.update(b, off, len) }
                    }
                    zip.getInputStream(entry).use { quota.copy(it, output, checkCancelled) }
                    require(crc.value == entry.crc) { "Snapshot ZIP checksum mismatch" }
                }
            }
        }
    }
}
