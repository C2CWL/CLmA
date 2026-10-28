package com.clma.app.data.ai.tools.local

import android.content.Context
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.browser.*
import com.clma.app.data.ai.tools.ToolInvocationContext
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.secrets.AgentSecretsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Wrap legacy tools once: every call resolves ownership before touching a WebView. */
fun scopedBrowserTool(name: String, context: Context, invocationContext: ToolInvocationContext?, settings: SettingsStore, vault: AgentSecretsStore): Tool? {
    val owner = invocationContext?.callerConversationId
    val legacy = createBrowserTool(name, context, invocationContext)
    val extra = name in BROWSER_UPGRADE_TOOLS
    if (legacy == null && !extra) return null
    val base = legacy ?: browserUpgradeTool(name, context, settings, vault, owner)
    return base.copy(
        description = base.description + " Optional tab_id targets an owned tab; session_id must match the calling conversation. Tabs share the application login profile, not private/incognito storage. browser_done ends only the task. Network settings are process-wide. Use browser_task_extend to resume an expired/cancelled task.",
        parameters = {
            val schema = base.parameters() as? InputSchema.Obj
            InputSchema.Obj(buildJsonObject {
                schema?.properties?.forEach { (k, v) -> put(k, v) }
                put("tab_id", field("string", "Optional owned tab ID; defaults to this session's active tab"))
                put("session_id", field("string", "Optional; must equal caller session ID"))
            }, schema?.required)
        },
        execute = { input ->
            val p = input.jsonObject
            val requested = p["tab_id"]?.jsonPrimitive?.contentOrNull
            val session = p["session_id"]?.jsonPrimitive?.contentOrNull
            suspend fun invoke(): List<UIMessagePart> = base.execute(input)
            try {
                if (owner == null || (session != null && session != owner)) {
                    browserText(browserError("browser_session_identity_required"))
                } else if (extra) {
                    withTimeoutOrNull(BrowserController.perToolTimeoutMs) { invoke() }
                        ?: browserText(browserError("browser_tool_timeout"))
                } else {
                    val tab = BrowserSessions.get(owner, requested)
                    if (requested != null && tab == null) browserText(browserError("tab_not_found_or_not_owned"))
                    else if (tab != null) BrowserSessions.use(tab) {
                        withTimeoutOrNull(BrowserController.perToolTimeoutMs) {
                            BrowserNetworkController.ready(settings)
                            if (name == BrowserToolDefaults.OPEN) {
                                tab.clock.start(BrowserController.singleTaskTimeoutMs)
                                if (!tab.clock.allowed()) browserText(browserError("browser_task_${tab.clock.status()}"))
                                else {
                                    val url = p["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    requireBrowserUrl(url)
                                    withContext(Dispatchers.Main) { BrowserNetworkController.navigate(tab.view, url) }
                                    tab.view.awaitReadyState(8_000)
                                    browserText(tabInfo(tab))
                                }
                            } else invoke()
                        } ?: browserText(browserError("browser_tool_timeout"))
                    } else if (name != BrowserToolDefaults.OPEN) browserText(browserError("browser_not_open"))
                    else BrowserSessions.legacyMutex.withLock {
                        if (BrowserSessions.legacyOwner != null && BrowserSessions.legacyOwner != owner) {
                            // A second conversation must not steal the globally bound foreground view.
                            browserText(browserError("use_browser_tab_open_for_parallel_session"))
                        } else {
                            BrowserNetworkController.ready(settings)
                            BrowserSessions.legacyOwner = owner
                            val result = invoke()
                            val view = withContext(Dispatchers.Main) { BrowserController.activeWebView() }
                            if (view != null) {
                                val adopted = BrowserSessions.adopt(owner, view)
                                adopted.clock.start(BrowserController.singleTaskTimeoutMs)
                                result + browserText(tabInfo(adopted))
                            } else result
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { browserText(browserError("invalid_or_unsupported_browser_configuration")) }
            catch (e: Exception) { browserText(browserError("browser_operation_failed", e.javaClass.simpleName)) }
        },
    )
}

val BROWSER_UPGRADE_TOOLS = listOf(
    "browser_tab_open", "browser_tab_list", "browser_tab_select", "browser_tab_close",
    "browser_task_status", "browser_task_extend", "browser_task_cancel",
    "browser_download", "browser_download_status", "browser_download_cancel", "browser_export_pdf",
    "browser_site_status", "browser_site_clear", "browser_cookie_import", "browser_cookie_export",
    "browser_network_status", "browser_network_configure", "browser_network_export_har",
)

internal fun field(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }
internal fun browserText(value: JsonObject): List<UIMessagePart> = listOf(UIMessagePart.Text(value.toString()))
internal fun browserError(code: String, detail: String? = null) = buildJsonObject { put("success", false); put("error", code); detail?.let { put("detail", it) } }
internal fun requireBrowserUrl(url: String) {
    val u = java.net.URI(url)
    require(u.scheme in setOf("http", "https") && u.host != null && u.userInfo == null) { "http_https_url_required" }
}
internal suspend fun tabInfo(tab: BrowserSessions.Tab): JsonObject = withContext(Dispatchers.Main) {
    buildJsonObject {
        put("success", true); put("session_id", tab.owner); put("tab_id", tab.id)
        put("task_state", tab.clock.status()); put("deadline_elapsed_ms", tab.clock.deadline)
        put("current_url", tab.view.url.orEmpty()); put("title", tab.view.title.orEmpty())
        put("profile", "shared_application_profile"); put("private_isolation", false)
    }
}

private fun browserUpgradeTool(name: String, context: Context, settings: SettingsStore, vault: AgentSecretsStore, owner: String?): Tool = Tool(
    name = name,
    description = when (name) {
        "browser_tab_open" -> "Open an offscreen tab, max 4 per session / 12 process-wide. private=true returns unsupported; cookies are shared."
        "browser_network_configure" -> "Set process-wide browser mode: follow_app (application proxy) or direct (no application proxy, system VPN still applies). Waits for WebView callback; no fallback on proxy failure. Optional user_agent and exact header_host + headers (Accept-Language, DNT, Cache-Control only; first navigation only). Authenticated proxies unsupported."
        "browser_cookie_import" -> "Import a single host-only Set-Cookie record from cookie_secret in the on-device vault. No raw credentials in arguments/results. Always requires approval."
        "browser_cookie_export" -> "Export this site's Cookie request header to cookie_secret in the on-device vault, not chat/files. This is not a full-fidelity cookie backup (HttpOnly/expiry metadata unavailable). Always requires approval."
        "browser_site_clear" -> "Clear origin WebStorage only. Cookie deletion is unsupported without complete attributes; never clears global cookies. Always requires approval."
        "browser_download" -> "Start bounded background HTTP(S) download to app-private storage. Returns job_id. Uses browser proxy mode; redirects, blob URLs and private targets unsupported. Credentials are not attached."
        "browser_export_pdf" -> "Export current tab as PDF in private storage; returns job_id. Query browser_download_status for completion/file/error."
        "browser_network_export_har" -> "Export redacted HAR skeleton with partial navigation observations; NOT a complete request/subresource capture. No cookies, headers, query strings or bodies."
        else -> "Browser session operation: $name. Jobs and tabs are scoped to the calling conversation."
    },
    parameters = { InputSchema.Obj(buildJsonObject {
        when (name) {
            "browser_tab_open" -> { put("url", field("string", "HTTP(S) URL")); put("private", field("boolean", "Must be false; isolated profiles unsupported")) }
            "browser_task_extend" -> put("minutes", field("integer", "New remaining budget, 1..60 minutes"))
            "browser_download" -> put("url", field("string", "Public HTTP(S) download URL"))
            "browser_download_status", "browser_download_cancel" -> put("job_id", field("string", "Download or PDF job ID"))
            "browser_site_status", "browser_site_clear", "browser_cookie_import", "browser_cookie_export" -> {
                put("url", field("string", "Exact HTTP(S) origin; no path/query/fragment"))
                put("cookie_secret", field("string", "Vault secret name; never raw Cookie data"))
            }
            "browser_network_configure" -> {
                put("mode", field("string", "follow_app or direct")); put("user_agent", field("string", "Optional UA override"))
                put("header_host", field("string", "Exact host for navigation headers")); put("headers", field("object", "Allowlisted non-secret headers"))
            }
        }
    }) },
    needsApproval = { name in setOf("browser_cookie_import", "browser_cookie_export", "browser_site_clear", "browser_network_configure", "browser_download", "browser_export_pdf", "browser_tab_close", "browser_task_cancel", "browser_task_extend", "browser_network_export_har") },
    execute = { input ->
        val p = input.jsonObject
        fun str(key: String) = p[key]?.jsonPrimitive?.contentOrNull
        val session = requireNotNull(owner)
        val tab = BrowserSessions.get(session, str("tab_id"))
        val out: JsonObject = when (name) {
            "browser_tab_open" -> {
                if (p["private"]?.jsonPrimitive?.booleanOrNull == true) browserError("isolated_profile_unsupported")
                else {
                    val url = str("url") ?: "about:blank"
                    if (url != "about:blank") requireBrowserUrl(url)
                    BrowserNetworkController.ready(settings)
                    val created = BrowserSessions.create(context, session)
                    BrowserSessions.use(created) {
                        created.clock.start(BrowserController.singleTaskTimeoutMs)
                        withContext(Dispatchers.Main) { BrowserNetworkController.navigate(created.view, url) }
                        created.view.awaitReadyState(8_000)
                        tabInfo(created)
                    }
                }
            }
            "browser_tab_list" -> buildJsonObject { put("success", true); put("tabs", JsonArray(BrowserSessions.list(session).map { tabInfo(it) })) }
            "browser_network_status" -> buildJsonObject { put("success", true); put("mode", BrowserNetworkController.mode); put("scope", "process-wide"); put("system_vpn_applies", true); put("capture", "partial_navigation_only") }
            "browser_network_configure" -> {
                BrowserNetworkController.configure(settings, str("mode") ?: "follow_app", str("user_agent"), str("header_host"),
                    p["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap())
                buildJsonObject { put("success", true); put("mode", BrowserNetworkController.mode); put("scope", "process-wide"); put("system_vpn_applies", true) }
            }
            "browser_network_export_har" -> withContext(Dispatchers.IO) {
                val file = java.io.File(context.filesDir, "browser-exports/${java.util.UUID.randomUUID()}.har")
                file.parentFile?.mkdirs(); file.writeText(BrowserNetworkController.har().toString())
                buildJsonObject { put("success", true); put("file", file.absolutePath); put("complete_capture", false) }
            }
            "browser_download" -> BrowserArtifacts.download(context, settings, session, str("url").orEmpty())
            "browser_download_status" -> BrowserArtifacts.status(session, str("job_id").orEmpty())
            "browser_download_cancel" -> BrowserArtifacts.cancel(session, str("job_id").orEmpty())
            "browser_site_status", "browser_site_clear", "browser_cookie_import", "browser_cookie_export" -> browserSiteOperation(name, p, vault)
            else -> if (tab == null) browserError("tab_not_found_or_not_owned") else when (name) {
                "browser_tab_select" -> { BrowserSessions.select(tab); tabInfo(tab) }
                "browser_tab_close" -> { BrowserSessions.close(tab); buildJsonObject { put("success", true) } }
                "browser_task_status" -> tabInfo(tab)
                "browser_task_extend" -> { tab.clock.extend(p["minutes"]?.jsonPrimitive?.intOrNull ?: 5); tabInfo(tab) }
                "browser_task_cancel" -> {
                    tab.clock.cancel(); tab.job?.cancel()
                    withContext(Dispatchers.Main) { tab.view.stopLoading() }
                    tabInfo(tab)
                }
                "browser_export_pdf" -> BrowserSessions.use(tab) { BrowserArtifacts.pdf(context, session, tab.view) }
                else -> browserError("unsupported_browser_operation")
            }
        }
        browserText(out)
    },
)

private suspend fun browserSiteOperation(name: String, p: JsonObject, vault: AgentSecretsStore): JsonObject = withContext(Dispatchers.Main) {
    val url = p["url"]?.jsonPrimitive?.content.orEmpty()
    requireBrowserUrl(url)
    val uri = java.net.URI(url)
    require(uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/"))
    val cookies = android.webkit.CookieManager.getInstance()
    val secret = p["cookie_secret"]?.jsonPrimitive?.content.orEmpty()
    when (name) {
        "browser_site_status" -> buildJsonObject {
            put("success", true); put("has_cookies", !cookies.getCookie(url).isNullOrEmpty()); put("profile", "shared_application_profile")
            put("full_cookie_backup_supported", false); put("cookie_site_clear_supported", false)
        }
        "browser_site_clear" -> {
            android.webkit.WebStorage.getInstance().deleteOrigin("${uri.scheme}://${uri.rawAuthority}")
            buildJsonObject { put("success", true); put("web_storage_clear_requested", true); put("cookies_cleared", false); put("cookie_clear_error", "complete_site_cookie_enumeration_unsupported") }
        }
        "browser_cookie_export" -> {
            require(vault.isValidName(secret) && !vault.has(secret))
            val value = cookies.getCookie(url)
            if (value.isNullOrEmpty()) browserError("no_site_cookies") else {
                check(vault.set(secret, value))
                buildJsonObject { put("success", true); put("cookie_secret", secret); put("format", "request_cookie_header_not_full_backup") }
            }
        }
        else -> {
            require(vault.isValidName(secret))
            val value = requireNotNull(vault.get(secret))
            // Only a single host-only Set-Cookie is accepted: no broad Domain injection.
            require(value.length <= 8192 && '\r' !in value && '\n' !in value && '=' in value.substringBefore(';'))
            require(!Regex("(?i)(^|;)\\s*domain\\s*=").containsMatchIn(value))
            val done = CompletableDeferred<Boolean>()
            cookies.setCookie(url, value) { done.complete(it) }
            val ok = done.await(); cookies.flush()
            buildJsonObject { put("success", ok); put("credentials_returned", false) }
        }
    }
}
