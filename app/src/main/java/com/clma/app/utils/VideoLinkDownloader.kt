package com.clma.app.utils

import com.clma.app.data.ai.net.GuardedDns
import com.clma.app.data.ai.net.hostIsBlockedLiteral
import com.clma.common.http.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal object VideoLinkDownloader {
    private const val MAX_BYTES = 100L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(GuardedDns())
        .followRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data class Downloaded(val file: File) : Result
        data class Unavailable(val reason: String) : Result
    }

    suspend fun download(raw: String, directory: File): Result {
        var url = raw.toHttpUrlOrNull() ?: return Result.Unavailable("Invalid video link.")
        repeat(5) {
            if (VideoLinks.isPlatform(url.host)) {
                return Result.Unavailable("This is a video platform page. Ask the user to attach the video file or share a public direct file link.")
            }
            if (hostIsBlockedLiteral(url.host)) {
                return Result.Unavailable("The video link must point to a public address.")
            }
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
                return Result.Unavailable("Video links with embedded credentials are unsupported.")
            }
            val request = Request.Builder().url(url).build()
            client.newCall(request).await().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val next = response.header("Location")?.let(url::resolve)
                        ?: return Result.Unavailable("The video link has an invalid redirect.")
                    if (url.isHttps && !next.isHttps) {
                        return Result.Unavailable("The video link redirects to an insecure address.")
                    }
                    url = next
                    return@repeat
                }
                if (!response.isSuccessful) {
                    return Result.Unavailable("The public video download failed (HTTP ${response.code}).")
                }
                val body = response.body
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                val extension = VideoLinks.extension(url.encodedPath)
                val suffix = when (mime) {
                    "video/mp4" -> "mp4"
                    "video/webm" -> "webm"
                    "video/quicktime", "video/mov" -> "mov"
                    "video/avi", "video/x-msvideo" -> "avi"
                    "video/x-flv" -> "flv"
                    "video/wmv", "video/x-ms-wmv" -> "wmv"
                    "video/x-matroska" -> "mkv"
                    "video/3gpp" -> "3gp"
                    "video/mpeg" -> "mpeg"
                    else -> extension.takeIf { it in VideoLinks.extensions }
                }
                if (mime?.startsWith("video/") != true &&
                    !(mime in setOf(null, "application/octet-stream") && suffix != null)
                ) {
                    return Result.Unavailable("The link did not return a downloadable video file. Share a direct file link or attach the video.")
                }
                if (body.contentLength() > MAX_BYTES) return tooLarge()
                val file = File.createTempFile("video-", suffix?.let { ".$it" } ?: ".video", directory)
                var complete = false
                try {
                    val buffer = ByteArray(32 * 1024)
                    var bytes = 0L
                    file.outputStream().use { output ->
                        val input = body.byteStream()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            bytes += count
                            if (bytes > MAX_BYTES) return tooLarge()
                            output.write(buffer, 0, count)
                        }
                    }
                    if (bytes == 0L) return Result.Unavailable("The downloaded video file is empty.")
                    complete = true
                    return Result.Downloaded(file)
                } finally {
                    if (!complete) file.delete()
                }
            }
        }
        return Result.Unavailable("The video link redirected too many times.")
    }

    private fun tooLarge() = Result.Unavailable("The video download exceeds 100 MiB. Attach the file locally to use frame previews.")
}
