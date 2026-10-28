package com.clma.app.browser

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PageRange
import android.webkit.WebView
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.ai.net.isBlockedDownloadUrl
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Private, bounded artifact jobs. No DownloadManager bypass of browser proxy settings. */
object BrowserArtifacts {
    private const val MAX_BYTES = 100L * 1024 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Entry(val owner: String, val file: File) {
        @Volatile var state = "queued"
        @Volatile var bytes = 0L
        @Volatile var total = -1L
        @Volatile var error: String? = null
        @Volatile var job: Job? = null
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    @Synchronized private fun create(context: Context, owner: String, extension: String): Pair<String, Entry> {
        check(entries.values.count { it.state in setOf("queued", "running") } < 4) { "artifact_concurrency_limit" }
        check(entries.size < 100) { "artifact_session_limit" }
        val directory = File(context.filesDir, "browser-exports").apply { mkdirs() }
        check(directory.listFiles().orEmpty().sumOf { it.length() } < 500L * 1024 * 1024) { "artifact_storage_limit" }
        val id = UUID.randomUUID().toString()
        val entry = Entry(owner, File(directory, "$id.$extension"))
        entries[id] = entry
        return id to entry
    }
    fun status(owner: String, id: String): JsonObject {
        val e = entries[id]?.takeIf { it.owner == owner }
            ?: return buildJsonObject { put("success", false); put("error", "job_not_found_or_not_owned") }
        return buildJsonObject {
            put("success", true); put("job_id", id); put("state", e.state); put("bytes", e.bytes); put("total_bytes", e.total)
            e.error?.let { put("error", it) }
            if (e.state == "completed") put("file", e.file.absolutePath)
        }
    }
    fun cancel(owner: String, id: String): JsonObject {
        val e = entries[id]?.takeIf { it.owner == owner } ?: return status(owner, id)
        if (e.state in setOf("queued", "running")) { e.state = "cancelled"; e.job?.cancel() }
        return status(owner, id)
    }
    fun download(context: Context, settings: SettingsStore, owner: String, url: String): JsonObject {
        val uri = java.net.URI(url)
        require(uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null)
        val client = BrowserNetworkController.client(settings)
        // Proxy-side DNS cannot be checked/pinned by GuardedDns; refuse instead of bypassing SSRF protection.
        require(client.proxy == java.net.Proxy.NO_PROXY) { "proxy_download_target_validation_unsupported" }
        val (id, e) = create(context, owner, "download")
        e.job = scope.launch(start = CoroutineStart.LAZY) {
            e.state = "running"
            try {
                withTimeout(10 * 60_000L) {
                    check(!isBlockedDownloadUrl(url)) { "blocked_private_address" }
                    val call = client.newCall(okhttp3.Request.Builder().url(url).build())
                    val cancellation = launch { try { awaitCancellation() } finally { call.cancel() } }
                    try {
                        call.execute().use { response ->
                            check(response.isSuccessful) { "http_${response.code}" }
                            val body = requireNotNull(response.body)
                            e.total = body.contentLength()
                            check(e.total <= MAX_BYTES) { "download_size_limit" }
                            body.byteStream().use { source ->
                                e.file.outputStream().use { target ->
                                    val buffer = ByteArray(32 * 1024)
                                    while (true) {
                                        ensureActive()
                                        val n = source.read(buffer)
                                        if (n < 0) break
                                        check(e.bytes + n <= MAX_BYTES) { "download_size_limit" }
                                        target.write(buffer, 0, n); e.bytes += n
                                    }
                                }
                            }
                        }
                    } finally { cancellation.cancel() }
                }
                e.state = "completed"
            } catch (ex: CancellationException) { e.state = "cancelled"; throw ex }
            catch (ex: Exception) {
                e.state = "failed"
                e.error = when (ex) {
                    is java.net.UnknownHostException -> "dns_or_blocked_target"
                    is java.net.SocketTimeoutException -> "connection_or_read_timeout"
                    is javax.net.ssl.SSLException -> "tls_error"
                    else -> if (ex.message?.matches(Regex("http_[0-9]{3}|download_size_limit|blocked_private_address")) == true) ex.message else "download_failed"
                }
            } finally { if (e.state != "completed") e.file.delete() }
        }
        e.job!!.start()
        return status(owner, id)
    }
    fun pdf(context: Context, owner: String, view: WebView): JsonObject {
        val (id, e) = create(context, owner, "pdf")
        e.job = scope.launch(start = CoroutineStart.LAZY) {
            e.state = "running"
            try {
                withTimeout(120_000) { withContext(Dispatchers.Main) { writePdf(view, e.file) } }
                e.bytes = e.file.length(); e.state = "completed"
            } catch (ex: CancellationException) { e.state = "cancelled"; throw ex }
            catch (ex: Exception) { e.state = "failed"; e.error = "pdf_export_failed" }
            finally { if (e.state != "completed") e.file.delete() }
        }
        e.job!!.start()
        return status(owner, id)
    }
    private suspend fun writePdf(view: WebView, file: File) {
        val adapter = view.createPrintDocumentAdapter("CLmA browser export")
        val signal = CancellationSignal()
        val attributes = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "PDF", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
        try {
            val layout = CompletableDeferred<Unit>()
            adapter.onStart()
            adapter.onLayout(null, attributes, signal, object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) { layout.complete(Unit) }
                override fun onLayoutFailed(error: CharSequence?) { layout.completeExceptionally(IllegalStateException("pdf_layout_failed")) }
                override fun onLayoutCancelled() { layout.cancel() }
            }, null)
            layout.await()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                val write = CompletableDeferred<Unit>()
                adapter.onWrite(arrayOf(PageRange.ALL_PAGES), descriptor, signal, object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>?) { write.complete(Unit) }
                    override fun onWriteFailed(error: CharSequence?) { write.completeExceptionally(IllegalStateException("pdf_write_failed")) }
                    override fun onWriteCancelled() { write.cancel() }
                })
                write.await()
            }
        } finally { signal.cancel(); adapter.onFinish() }
    }
}
