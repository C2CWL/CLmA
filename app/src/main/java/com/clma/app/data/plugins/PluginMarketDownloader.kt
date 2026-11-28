package com.clma.app.data.plugins

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class PluginMarketDownloader(
    private val context: Context,
    private val client: OkHttpClient,
) {
    companion object {
        private const val MAX_BYTES = 50L * 1024L * 1024L
    }

    suspend fun download(entry: PluginMarketEntry): File = withContext(Dispatchers.IO) {
        val url = entry.downloadUrl ?: error("This plugin has no download URL")
        val dir = File(context.noBackupFilesDir, "plugin-market-downloads").apply { mkdirs() }
        val file = File(dir, "plugin-${System.currentTimeMillis()}-${safeFilePart(entry.id)}.apk")

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.android.package-archive")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
                val body = response.body ?: error("Empty download response")
                val declaredSize = entry.assetSize ?: body.contentLength()
                if (declaredSize > MAX_BYTES) error("Plugin APK exceeds ${MAX_BYTES / 1024 / 1024} MB limit")
                body.byteStream().use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BYTES) error("Plugin APK exceeds ${MAX_BYTES / 1024 / 1024} MB limit")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }

            val expectedSha256 = entry.downloadSha256
            if (!expectedSha256.isNullOrBlank()) {
                val actualSha256 = sha256(file)
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    error("SHA256 mismatch")
                }
            }
            file
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun safeFilePart(value: String): String = value
        .map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
        .joinToString("")
        .take(80)
}
