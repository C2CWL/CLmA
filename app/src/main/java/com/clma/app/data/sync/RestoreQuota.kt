package com.clma.app.data.sync

import java.io.InputStream
import java.io.OutputStream

/** Limits actual decompressed bytes, not just untrusted ZIP metadata. */
internal class RestoreQuota(
    private val maxEntries: Int = 100_000,
    private val maxEntryBytes: Long = 2L * 1024 * 1024 * 1024,
    private val maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
    private val availableBytes: () -> Long,
) {
    private var entries = 0
    private var totalBytes = 0L

    fun countEntry() {
        require(++entries <= maxEntries) { "Backup has too many entries" }
    }

    fun copy(input: InputStream, output: OutputStream, checkCancelled: () -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            checkCancelled()
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            require(n.toLong() <= maxEntryBytes - entryBytes) { "Backup entry exceeds size limit" }
            require(n.toLong() <= maxTotalBytes - totalBytes) { "Backup exceeds total size limit" }
            require(n.toLong() <= availableBytes()) { "Not enough space to restore backup" }
            output.write(buffer, 0, n)
            entryBytes += n
            totalBytes += n
        }
    }
}
