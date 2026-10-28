package com.clma.app.browser

import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.network.toProxyOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.Executor
import kotlinx.serialization.json.*

/** WebView proxy overrides are process-wide, never per tab. No direct fallback rule. */
object BrowserNetworkController {
    private val mutex = Mutex()
    @Volatile var mode: String = "follow_app"
        private set
    private var applied: String? = null
    private val events = ArrayDeque<JsonObject>()
    @Volatile var userAgent: String? = null
        private set
    private var headerHost: String? = null
    private var headers: Map<String, String> = emptyMap()
    private val safeHeaders = setOf("accept-language", "dnt", "cache-control")

    suspend fun configure(settings: SettingsStore, requested: String, ua: String?, host: String?, values: Map<String, String>) {
        require(requested in setOf("follow_app", "direct")) { "invalid_network_mode" }
        require(ua == null || (ua.length in 1..512 && ua.none { it == '\r' || it == '\n' })) { "invalid_user_agent" }
        require(values.keys.all { it.lowercase() in safeHeaders } && values.values.all { it.length <= 512 && '\r' !in it && '\n' !in it }) { "unsafe_header" }
        require(values.isEmpty() || host?.matches(Regex("[A-Za-z0-9.-]+")) == true) { "exact_header_host_required" }
        mutex.withLock {
            apply(settings, requested)
            mode = requested
            userAgent = ua
            headerHost = host?.lowercase()
            headers = values
        }
    }
    suspend fun ready(settings: SettingsStore) = mutex.withLock { apply(settings, mode) }
    private suspend fun apply(settings: SettingsStore, requested: String) {
        val network = settings.settingsFlow.value.networkSetting
        val raw = if (requested == "follow_app") network.proxyUrl.trim() else ""
        val key = "$requested:$raw"
        if (applied == key) return
        val proxy = raw.takeIf { it.isNotEmpty() }?.toProxyOrNull()
        require(raw.isEmpty() || proxy != null) { "invalid_application_proxy" }
        // WebView cannot safely obtain authenticated proxy credentials via this API.
        require(raw.isEmpty() || network.proxyUsername.isEmpty()) { "authenticated_webview_proxy_unsupported" }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            check(raw.isEmpty() && requested == "follow_app" && applied == null) { "webview_proxy_override_unsupported" }
            return
        }
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            val executor = Executor { it.run() }
            if (requested == "follow_app" && raw.isEmpty()) {
                ProxyController.getInstance().clearProxyOverride(executor) { done.complete(Unit) }
            } else {
                val rule = if (proxy == null) "direct://" else {
                    val address = proxy.address() as java.net.InetSocketAddress
                    val scheme = if (proxy.type() == Proxy.Type.SOCKS) "socks" else "http"
                    "$scheme://${address.hostString}:${address.port}"
                }
                ProxyController.getInstance().setProxyOverride(
                    ProxyConfig.Builder().addProxyRule(rule).build(), executor,
                ) { done.complete(Unit) }
            }
        }
        // Await the actual callback; cancellation must not report a successful switch.
        done.await()
        applied = key
    }
    fun client(settings: SettingsStore): OkHttpClient {
        val n = settings.settingsFlow.value.networkSetting
        val raw = if (mode == "follow_app") n.proxyUrl.trim() else ""
        val proxy = if (raw.isEmpty()) Proxy.NO_PROXY else requireNotNull(raw.toProxyOrNull())
        require(raw.isEmpty() || n.proxyUsername.isEmpty()) { "authenticated_proxy_unsupported" }
        return OkHttpClient.Builder().proxy(proxy)
            .dns(com.clma.app.data.ai.net.GuardedDns())
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    fun navigate(view: WebView, url: String) {
        userAgent?.let { view.settings.userAgentString = it }
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
        view.loadUrl(url, synchronized(this) { if (host == headerHost) headers else emptyMap() })
        observe(url, "navigation")
    }
    @Synchronized fun observe(url: String?, kind: String) {
        // Never retain query, fragment, userinfo, request headers, response body or cookies.
        val origin = runCatching { java.net.URI(url ?: "").let { "${it.scheme}://${it.host}" } }.getOrDefault("redacted")
        events.addLast(buildJsonObject { put("origin", origin); put("kind", kind); put("at_ms", System.currentTimeMillis()) })
        while (events.size > 200) events.removeFirst()
    }
    @Synchronized fun har(): JsonObject = buildJsonObject {
        put("log", buildJsonObject {
            put("version", "1.2"); put("creator", buildJsonObject { put("name", "CLmA"); put("version", "1") })
            put("entries", JsonArray(emptyList()))
            put("_observations", JsonArray(events.toList()))
            put("comment", "Partial WebView navigation observations only; not a full network capture. URLs reduced to origins; no headers, credentials or bodies.")
        })
    }
}
