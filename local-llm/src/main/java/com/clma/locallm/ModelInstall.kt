package com.clma.locallm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

private const val TAG = "ModelInstall"

/**
 * Downloads a local-LLM model file from a URL into app-private storage.
 *
 * Resume-aware: a leftover `.partial` from a prior interrupted attempt triggers a
 * `Range: bytes=<existing-size>-` request, the server's 206 Partial Content response is
 * appended in place, and progress emits start at the resumed offset. Server-side fall-
 * backs: 200 OK → silently overwrite from 0; 416 → discard the stale partial and retry.
 *
 * Atomic rename to the final filename only on success, after a magic-byte integrity
 * check. Interrupted downloads retain URL-specific partials for later resume.
 *
 * Files land under `${context.filesDir}/local-models/llamacpp/<basename>`.
 */
object ModelInstall {

    private const val LOCAL_MODELS_DIRNAME = "local-models"

    /**
     * Progress events emitted by [download]. Terminal events are [Done] and [Failed].
     */
    sealed class Progress {
        data class Started(val totalBytes: Long?) : Progress()
        data class Tick(val bytesRead: Long, val totalBytes: Long?) : Progress()
        data class Done(val file: File) : Progress()
        data class Failed(val cause: Throwable) : Progress()
    }

    /**
     * Normalise a HuggingFace URL so that both the viewer (blob) form and the direct
     * download (resolve) form work.
     *
     * HuggingFace "view file" URLs look like:
     *   https://huggingface.co/<user>/<repo>/blob/main/<file>
     * The actual download URL is:
     *   https://huggingface.co/<user>/<repo>/resolve/main/<file>
     *
     * Pasting the viewer URL into the manual-install field would previously succeed
     * HTTP-wise (200 OK) but return an HTML page, not the model binary. This transform
     * converts blob → resolve for any huggingface.co path. Non-HF URLs are returned
     * unchanged.
     */
    fun normalizeHuggingFaceUrl(url: String): String {
        if (!url.contains("huggingface.co")) return url
        // Single regex handles all branches (main, dev, v1, etc.); the earlier literal
        // replace for /blob/main/ was redundant since the regex already covers it.
        return url.replace(Regex("/blob/([^/]+)/")) { m -> "/resolve/${m.groupValues[1]}/" }
    }

    fun isValidDownloadUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            val parsed = java.net.URI(url)
            parsed.scheme == "https" && parsed.host?.isNotBlank() == true
        }.getOrDefault(false)
    }

    fun runtimeForExtension(extension: String): LocalRuntime? = when (extension.lowercase()) {
        "gguf" -> LocalRuntime.LlamaCpp
        else -> null
    }

    fun extractFileNameFromUrl(url: String): String {
        val withoutQuery = url.substringBefore('?')
        return withoutQuery.substringAfterLast('/')
    }

    fun targetFile(baseDir: File, runtime: LocalRuntime, fileName: String): File {
        val sub = when (runtime) {
            LocalRuntime.LlamaCpp -> "llamacpp"
        }
        return File(File(baseDir, sub), fileName)
    }

    fun localModelsDir(context: Context): File =
        File(context.filesDir, LOCAL_MODELS_DIRNAME).apply { mkdirs() }

    /**
     * Returns true if the first [count] bytes of [buf] look like an HTML document.
     * Used to abort downloads that succeeded HTTP-wise but returned an error page.
     */
    fun looksLikeHtml(buf: ByteArray, count: Int): Boolean {
        val sample = String(buf, 0, count.coerceAtMost(256), Charsets.UTF_8)
            .trimStart()
            .lowercase()
        return sample.startsWith("<!doctype") ||
               sample.startsWith("<html") ||
               sample.startsWith("<head") ||
               sample.startsWith("<body") ||
               sample.startsWith("<?xml")
    }

    /**
     * Returns true if [firstBytes] looks like a valid file of the type implied by
     * [extension]. Used as a post-download integrity check to refuse files that
     * downloaded successfully but whose contents are wrong (sparse-fill, server
     * misroute, partial corruption, etc.).
     */
    fun isValidMagicForExtension(extension: String, firstBytes: ByteArray): Boolean {
        if (firstBytes.size < 4) return false
        return when (extension.lowercase()) {
            "gguf" -> {
                // GGUF files start with the ASCII magic "GGUF"
                // (0x47 0x47 0x55 0x46). A truncated download or an HTML error page
                // served in its place falls through to this arm without it and was
                // previously accepted as a model.
                firstBytes[0] == 0x47.toByte() && firstBytes[1] == 0x47.toByte() &&
                    firstBytes[2] == 0x55.toByte() && firstBytes[3] == 0x46.toByte()
            }
            else -> {
                // Unknown extension — accept by default but reject obvious zeros / HTML.
                !looksLikeHtml(firstBytes, firstBytes.size) && firstBytes.any { it != 0x00.toByte() }
            }
        }
    }

    /**
     * Download [url] into [target], emitting progress as it goes. Atomic rename to
     * [target] only on success after a magic-byte integrity check.
     *
     * HuggingFace blob URLs are automatically normalised to resolve URLs before the
     * request is issued. Callers may also pre-normalise with [normalizeHuggingFaceUrl].
     */
    fun download(
        client: OkHttpClient,
        url: String,
        target: File,
        mirrorEnabled: Boolean = false,
    ): Flow<Progress> = flow {
        val sources = ModelDownloadPolicy.sources(normalizeHuggingFaceUrl(url), mirrorEnabled)
        val downloadClient = client.newBuilder()
            .readTimeout(ModelDownloadPolicy.STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        for (attempt in 1..ModelDownloadPolicy.MAX_ATTEMPTS) {
            val source = sources[(attempt - 1) % sources.size]
            var failure: Progress.Failed? = null
            downloadOnce(downloadClient, source, target)
                .catch { error ->
                    if (error is IOException) emit(Progress.Failed(error)) else throw error
                }.collect { progress ->
                    when (progress) {
                        is Progress.Failed -> failure = progress
                        is Progress.Done -> {
                            sources.forEach { ModelDownloadPolicy.partialFile(target, it).delete() }
                            emit(progress)
                        }
                        else -> emit(progress)
                    }
                }
            val failed = failure ?: return@flow
            val cause = failed.cause
            val retryable = cause is RetryableDownloadFailure ||
                (cause is IOException && cause !is SSLException)
            if (!retryable || attempt == ModelDownloadPolicy.MAX_ATTEMPTS) {
                emit(failed)
                return@flow
            }
            delay(ModelDownloadPolicy.backoffMillis(attempt + 1))
        }
    }.flowOn(Dispatchers.IO)

    private val contentRange = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")

    private class RetryableDownloadFailure(message: String) : IllegalStateException(message)

    private fun downloadOnce(
        client: OkHttpClient,
        normalized: String,
        target: File,
    ): Flow<Progress> = flow {
        target.parentFile?.mkdirs()
        val partial = ModelDownloadPolicy.partialFile(target, normalized)
        // Resume only from this source URL's partial; imports and mirrors use separate files.
        val resumeFrom = if (partial.exists() && partial.length() > 0) partial.length() else 0L

        // The partial records the raw byte offsets. Do not negotiate compressed bodies.
        val requestBuilder = Request.Builder().url(normalized).header("Accept-Encoding", "identity")
        if (resumeFrom > 0) {
            requestBuilder.header("Range", "bytes=$resumeFrom-")
        }
        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            // 416 Range Not Satisfiable — partial bigger than server file (corruption or
            // server-side replacement). Restart fresh.
            if (response.code == 416 && resumeFrom > 0) {
                Log.w(TAG, "Server returned 416 (Range Not Satisfiable) — partial=$resumeFrom; restarting from 0")
                val cause = if (partial.delete() || !partial.exists()) {
                    RetryableDownloadFailure("Server rejected the saved range; restarting download")
                } else IllegalStateException("Cannot remove the stale download partial")
                emit(Progress.Failed(cause))
                return@flow
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "Download failed: HTTP ${response.code}")
                val message = "HTTP ${response.code}"
                emit(Progress.Failed(if (ModelDownloadPolicy.isTransientHttpCode(response.code)) {
                    RetryableDownloadFailure(message)
                } else IllegalStateException(message)))
                return@flow
            }

            // Reject HTML responses immediately — a 200 OK from HuggingFace viewer pages,
            // Cloudflare error pages, etc. would otherwise land silently as an HTML file.
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) {
                Log.w(TAG, "Download rejected: HTML content-type '$contentType'")
                emit(Progress.Failed(IllegalStateException(
                    "Server returned an HTML page, not a model file. " +
                    "Use a /resolve/ URL — for HuggingFace, /blob/ paths auto-rewrite to /resolve/."
                )))
                return@flow
            }

            // 206 Partial Content means the server honoured our Range header → append to
            // the existing partial. 200 OK means the server ignored the Range header (or
            // we didn't send one) → overwrite the partial from byte 0. Total file size
            // comes from Content-Range when resuming (`bytes 1024-4095/4096`); from
            // Content-Length when fresh.
            val isResume = response.code == 206 && resumeFrom > 0
            val total: Long? = if (response.code == 206) {
                val range = contentRange.matchEntire(response.header("Content-Range").orEmpty())
                val start = range?.groupValues?.get(1)?.toLongOrNull()
                val end = range?.groupValues?.get(2)?.toLongOrNull()
                val size = range?.groupValues?.get(3)?.toLongOrNull()
                if (!isResume || start != resumeFrom || end == null || size == null ||
                    end < resumeFrom || end != size - 1
                ) {
                    emit(Progress.Failed(IllegalStateException("Unexpected Content-Range for resumed download")))
                    return@flow
                }
                size
            } else {
                response.header("Content-Length")?.toLongOrNull()
            }
            emit(Progress.Started(total))

            val effectiveResumeFrom = if (isResume) resumeFrom else 0L
            if (effectiveResumeFrom > 0) {
                // Surface the current bytes-on-disk so the UI's first tick shows the
                // right starting percentage instead of jumping from 0% to the resumed
                // figure on the next data chunk.
                emit(Progress.Tick(effectiveResumeFrom, total))
            } else if (resumeFrom > 0) {
                // We sent Range but server returned 200 — clear the partial so we don't
                // double-write its old contents.
                Log.w(TAG, "Server returned 200 (Range not honoured); restarting partial from 0")
                runCatching { partial.delete() }
            }

            val body = response.body

            // Skip the HTML magic-byte sniff when resuming — those bytes are already on
            // disk from the prior attempt, and the body now starts mid-file. A false
            // positive would otherwise nuke valid partials.
            var sniffed = isResume
            var bailed = false
            // FileOutputStream(file, append=true) when resuming so we extend rather than
            // truncate. partial.outputStream() truncates implicitly.
            val out = if (isResume) {
                java.io.FileOutputStream(partial, /* append = */ true)
            } else {
                partial.outputStream()
            }
            // Wrap the read loop. SocketException ("Software caused connection abort"),
            // SSL handshake errors, and IOException all happen mid-stream when the OS
            // tears down the socket — user backgrounded the app, WiFi flipped, server
            // closed the connection, etc. Without this catch the throw propagates up
            // through .collect{}, lands on whatever dispatcher the collector runs on
            // (Main, in our case), and crashes the process. The partial bytes already
            // on disk are intact and resumable on the next Install tap because of the
            // Range-resume logic above.
            var totalRead = effectiveResumeFrom
            val ioFailure: IOException? = try {
                out.use { sink ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            if (!sniffed) {
                                sniffed = true
                                if (looksLikeHtml(buf, n)) {
                                    bailed = true
                                    break
                                }
                            }
                            sink.write(buf, 0, n)
                            totalRead += n
                            emit(Progress.Tick(totalRead, total))
                        }
                    }
                }
                null
            } catch (e: IOException) {
                e
            }
            if (ioFailure != null) {
                Log.w(TAG, "Download read interrupted: ${ioFailure::class.simpleName}")
                emit(Progress.Failed(ioFailure.takeIf { it is SSLException } ?: IOException(
                    "Download interrupted (${ioFailure::class.simpleName}: ${ioFailure.message}). " +
                    "Tap Install again to resume from where it stopped.",
                    ioFailure,
                )))
                return@flow
            }

            if (bailed) {
                runCatching { partial.delete() }
                Log.w(TAG, "Download aborted: HTML magic bytes detected in response body")
                emit(Progress.Failed(IllegalStateException(
                    "Server returned an HTML page (magic-byte check failed). Check the URL."
                )))
                return@flow
            }

            // The read loop above treats a clean EOF (n <= 0) as "done", but a server or
            // proxy can close the connection early without ever throwing - the socket just
            // ends. Content-Length (or, resuming, Content-Range's total) is the only way to
            // tell that apart from a real completed download; without this check a short
            // read silently passed the magic-byte check below whenever the truncation
            // landed after the first few bytes, and got registered as a working model.
            if (total != null && totalRead > total) {
                partial.delete()
                emit(Progress.Failed(IllegalStateException("Download exceeds the declared size")))
                return@flow
            }
            if (total != null && totalRead < total) {
                Log.w(TAG, "Download short: got $totalRead of $total bytes")
                emit(Progress.Failed(java.io.IOException(
                    "Download incomplete: received $totalRead of $total bytes. " +
                    "Tap Install again to resume from where it stopped."
                )))
                return@flow
            }

            // Post-download magic-byte validation: ensure the file we just wrote is
            // actually a valid model file. Catches sparse-file / partial-write
            // corruption, server misroutes, and other ways the bytes can go wrong.
            val ext = target.name.substringAfterLast('.', "").lowercase()
            val firstBytes = ByteArray(16)
            val bytesRead = partial.inputStream().use { it.read(firstBytes) }
            if (bytesRead <= 0 || !isValidMagicForExtension(ext, firstBytes.copyOf(bytesRead))) {
                val hexDump = firstBytes.take(8).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
                Log.w(TAG, "Magic-byte check failed for .$ext — first bytes: $hexDump")
                runCatching { partial.delete() }
                emit(Progress.Failed(IllegalStateException(
                    "Downloaded file does not have the expected magic bytes for .$ext " +
                    "(starts with: $hexDump). " +
                    "The server may have served the wrong content, or the download was corrupted."
                )))
                return@flow
            }

            // Do not unlink the existing model before an atomic same-directory rename.
            val renamed = partial.renameTo(target)
            if (!renamed) {
                // renameTo() returns false silently on cross-partition moves or permission errors.
                Log.e(TAG, "Rename failed: ${partial.absolutePath} -> ${target.absolutePath}")
                runCatching { partial.delete() }
                emit(Progress.Failed(IllegalStateException(
                    "Failed to move downloaded file to its final location: ${target.absolutePath}. " +
                    "Check available storage and permissions."
                )))
                return@flow
            }
            Log.i(TAG, "Download complete: ${target.absolutePath}")
            emit(Progress.Done(target))
        }
    }

    /**
     * Copies [input] into [target], emitting the same [Progress] events as [download]. Used
     * to import a model file the user already has on-device (SAF file picker): there is
     * nothing to fetch or resume, just a local stream copy, but sharing [Progress] lets
     * callers register a finished import exactly like a finished download.
     *
     * The magic-byte check runs against [expectedExtension] on the FIRST bytes read, before
     * anything is written to disk — a mis-picked file fails immediately instead of after
     * copying gigabytes for nothing. [expectedExtension] is passed explicitly rather than
     * derived from [target]'s name, since the picker lets the user choose any file regardless
     * of its own claimed name/extension.
     */
    fun copyFromStream(
        input: InputStream,
        target: File,
        totalBytes: Long?,
        expectedExtension: String,
    ): Flow<Progress> = flow {
        target.parentFile?.mkdirs()
        val partial = File(target.absolutePath + ".partial")
        emit(Progress.Started(totalBytes))

        var invalidMagic = false
        val ioFailure: Throwable? = try {
            input.use { source ->
                partial.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    // InputStream.read(ByteArray) is only guaranteed to return at least one
                    // byte, not four — a network-backed SAF provider (Google Drive and
                    // similar) can hand back 1-3 bytes on the very first read. Accumulate
                    // across reads into a dedicated 4-byte buffer until either 4 bytes are
                    // in hand or the stream hits EOF, then validate once. Every byte read
                    // during accumulation is still written to sink below, so the copy stays
                    // byte-exact regardless of how the reads happen to chunk.
                    val sniffBuf = ByteArray(4)
                    var sniffLen = 0
                    var sniffed = false
                    var totalRead = 0L
                    while (true) {
                        val n = source.read(buf)
                        if (n <= 0) {
                            // EOF before 4 bytes accumulated (including a 0-byte file, where
                            // sniffLen stays 0): validate whatever was collected.
                            // isValidMagicForExtension rejects anything shorter than 4 bytes.
                            if (!sniffed) {
                                sniffed = true
                                if (!isValidMagicForExtension(expectedExtension, sniffBuf.copyOf(sniffLen))) {
                                    invalidMagic = true
                                }
                            }
                            break
                        }
                        sink.write(buf, 0, n)
                        totalRead += n
                        emit(Progress.Tick(totalRead, totalBytes))
                        if (!sniffed) {
                            val need = (4 - sniffLen).coerceAtMost(n)
                            System.arraycopy(buf, 0, sniffBuf, sniffLen, need)
                            sniffLen += need
                            if (sniffLen >= 4) {
                                sniffed = true
                                if (!isValidMagicForExtension(expectedExtension, sniffBuf)) {
                                    invalidMagic = true
                                    break
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: java.io.IOException) {
            e
        }

        if (invalidMagic) {
            Log.w(TAG, "Import rejected: first bytes are not a valid .$expectedExtension file")
            runCatching { partial.delete() }
            emit(Progress.Failed(IllegalArgumentException(
                "Selected file does not look like a valid .$expectedExtension model " +
                "(unexpected magic bytes)."
            )))
            return@flow
        }
        if (ioFailure != null) {
            Log.w(TAG, "Import read interrupted: ${ioFailure::class.simpleName}: ${ioFailure.message}")
            runCatching { partial.delete() }
            emit(Progress.Failed(java.io.IOException(
                "Import interrupted (${ioFailure::class.simpleName}: ${ioFailure.message}).",
                ioFailure,
            )))
            return@flow
        }

        if (target.exists()) target.delete()
        val renamed = partial.renameTo(target)
        if (!renamed) {
            Log.e(TAG, "Rename failed: ${partial.absolutePath} -> ${target.absolutePath}")
            runCatching { partial.delete() }
            emit(Progress.Failed(IllegalStateException(
                "Failed to move the imported file to its final location: ${target.absolutePath}."
            )))
            return@flow
        }
        Log.i(TAG, "Import complete: ${target.absolutePath}")
        emit(Progress.Done(target))
    }.flowOn(Dispatchers.IO)
}
