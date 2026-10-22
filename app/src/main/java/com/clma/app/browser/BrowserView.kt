package me.rerere.rikkahub.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A main-frame load failure to surface in [BrowserErrorOverlay]. */
private data class BrowserPageError(
    val url: String,
    /** WebViewClient error code (e.g. ERROR_HOST_LOOKUP) for network errors, null for HTTP. */
    val errorCode: Int?,
    /** HTTP status (>= 400) for server errors, null for network errors. */
    val httpStatus: Int?,
    val description: String?,
)

/** A pending SSL certificate decision — the dialog holds the handler until the user picks. */
private data class BrowserSslError(
    val url: String,
    val description: String?,
    val handler: SslErrorHandler,
)

/** Long-press hit target (link or image) shown in the context dialog. */
private data class BrowserHitTarget(
    val url: String,
    val isImage: Boolean,
)

/**
 * Top-level Compose root for [BrowserActivity]. Lays out:
 *   - [BrowserAddressBar] (top — read-only URL + nav controls)
 *   - [WebView] embedded via AndroidView (middle — fills weight=1f)
 *   - [BrowserAiStripe] (bottom — AI status, expandable to recent actions list)
 *
 * The WebView is created exactly once via `remember { WebView(ctx).apply { … } }` and
 * reused across recompositions — recreating it on every recomp would lose page state
 * and re-fetch the URL. Same pattern as Phase 19's WebViewPage.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun BrowserView(
    onWebViewReady: (WebView) -> Unit,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onLoadProgress: (Int) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCanGoForwardChange: (Boolean) -> Unit,
    canGoBackState: MutableState<Boolean>,
    canGoForwardState: MutableState<Boolean>,
    currentUrlState: MutableState<String>,
    currentTitleState: MutableState<String>,
    loadProgressState: MutableState<Int>,
    onClose: () -> Unit,
    onBackTap: () -> Unit,
    onForwardTap: () -> Unit,
    onRefreshTap: () -> Unit,
    onStopAi: () -> Unit,
    onNavigate: (String) -> Unit,
    searchEngineId: String,
    desktopMode: Boolean,
    onDesktopModeChange: (Boolean) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideCustomView: () -> Unit,
    initialUrl: String,
    conversationId: Uuid?,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // The Activity owns the WebView, but the overlay actions below (retry / open-external)
    // also need it — mirror the reference locally. Set once at creation; never changes.
    var webView by remember { mutableStateOf<WebView?>(null) }

    var pageError by remember { mutableStateOf<BrowserPageError?>(null) }
    var sslError by remember { mutableStateOf<BrowserSslError?>(null) }
    var hitTarget by remember { mutableStateOf<BrowserHitTarget?>(null) }

    Scaffold(
        topBar = {
            BrowserAddressBar(
                url = currentUrlState.value,
                canGoBack = canGoBackState.value,
                canGoForward = canGoForwardState.value,
                desktopMode = desktopMode,
                onClose = onClose,
                onBack = onBackTap,
                onForward = onForwardTap,
                onRefresh = onRefreshTap,
                onStopAi = onStopAi,
                onNavigate = { onNavigate(normalizeBrowserQuery(it, searchEngineId)) },
                onDesktopModeChange = onDesktopModeChange,
                onCopyLink = {
                    val link = currentUrlState.value
                    clipboard.setText(AnnotatedString(link))
                    Toast.makeText(ctx, R.string.browser_link_copied, Toast.LENGTH_SHORT).show()
                },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, currentUrlState.value)
                    }
                    runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
                },
                onOpenExternal = { openInExternalBrowser(ctx, currentUrlState.value) },
            )
        },
        bottomBar = {
            BrowserAiStripe(onStopAi = onStopAi)
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (loadProgressState.value in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgressState.value / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                WebViewHost(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    initialUrl = initialUrl,
                    onWebViewReady = {
                        webView = it
                        onWebViewReady(it)
                    },
                    onUrlChange = onUrlChange,
                    onTitleChange = onTitleChange,
                    onLoadProgress = onLoadProgress,
                    onCanGoBackChange = onCanGoBackChange,
                    onCanGoForwardChange = onCanGoForwardChange,
                    onPageStarted = { pageError = null },
                    onMainFrameError = { pageError = it },
                    onSslError = { sslError = it },
                    onLongPress = { hitTarget = it },
                    onShowFileChooser = onShowFileChooser,
                    onShowCustomView = onShowCustomView,
                    onHideCustomView = onHideCustomView,
                )
            }

            // Main-frame error overlay. Opaque and on top of the WebView: an unreachable page
            // otherwise renders as a silent white rectangle with zero actionable feedback.
            pageError?.let { err ->
                BrowserErrorOverlay(
                    error = err,
                    onRetry = {
                        pageError = null
                        webView?.reload()
                    },
                    onOpenExternal = {
                        openInExternalBrowser(ctx, err.url)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Bottom-anchored mini-chat overlay. Self-hides when conversationId is null
            // (manual launch from Settings without a chat context).
            BrowserMiniChat(
                conversationId = conversationId,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 8.dp),
            )
        }
    }

    // SSL certificate warning. Default posture is CANCEL (safe); the user may explicitly
    // proceed once. The handler is held until the dialog resolves — the page load stalls
    // meanwhile, which is exactly the WebView contract.
    sslError?.let { err ->
        AlertDialog(
            onDismissRequest = {
                err.handler.cancel()
                sslError = null
            },
            title = { Text(stringResource(R.string.browser_ssl_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.browser_ssl_body,
                        shortHostForDisplay(err.url).ifEmpty { err.url },
                        err.description.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    err.handler.proceed()
                    sslError = null
                }) {
                    Text(stringResource(R.string.browser_ssl_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    err.handler.cancel()
                    sslError = null
                }) {
                    Text(stringResource(R.string.browser_ssl_back))
                }
            },
        )
    }

    // Long-press context dialog (link / image).
    hitTarget?.let { hit ->
        AlertDialog(
            onDismissRequest = { hitTarget = null },
            title = {
                Text(
                    text = hit.url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(hit.url))
                        Toast.makeText(ctx, R.string.browser_link_copied, Toast.LENGTH_SHORT).show()
                        hitTarget = null
                    }) { Text(stringResource(R.string.browser_menu_copy_link)) }
                    if (hit.isImage) {
                        TextButton(onClick = {
                            enqueueBrowserDownload(ctx, hit.url, null, null)
                            hitTarget = null
                        }) { Text(stringResource(R.string.browser_hit_download_image)) }
                    }
                    TextButton(onClick = {
                        openInExternalBrowser(ctx, hit.url)
                        hitTarget = null
                    }) { Text(stringResource(R.string.browser_menu_open_external)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { hitTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** Full-screen opaque overlay for main-frame load failures, with retry / external-open. */
@Composable
private fun BrowserErrorOverlay(
    error: BrowserPageError,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = HugeIcons.Alert02,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (error.httpStatus != null) {
                    stringResource(R.string.browser_error_http_title, error.httpStatus)
                } else {
                    stringResource(R.string.browser_error_title)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = shortHostForDisplay(error.url).ifEmpty { error.url },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (!error.description.isNullOrBlank()) {
                Text(
                    text = error.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.browser_error_retry))
                }
                TextButton(onClick = onOpenExternal) {
                    Text(stringResource(R.string.browser_menu_open_external))
                }
            }
        }
    }
}

/**
 * Open a URL in the user's default external browser. Silently no-ops when nothing can
 * handle it (extremely unlikely for http(s), but this is also called with about:/file:).
 */
internal fun openInExternalBrowser(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/**
 * Route a non-http(s) navigation (tel:, mailto:, intent:, market:, …) to the system.
 * Returns true when the WebView should NOT load it (i.e. always — the fallback toast is
 * still "handled", because loading it would just render the WebView's raw error page).
 */
internal fun routeExternalScheme(ctx: Context, url: String): Boolean {
    runCatching {
        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
        }
        ctx.startActivity(intent)
    }.onFailure {
        // No app handles the scheme (no market:// handler, no dialer on a tablet…).
        Toast.makeText(ctx, R.string.browser_no_app_for_link, Toast.LENGTH_SHORT).show()
    }
    return true
}

/**
 * Enqueue a WebView download into the public Downloads dir via DownloadManager — the
 * WebView itself has NO download facility, so without a DownloadListener every download
 * link was a dead tap. Carries cookies + UA so authenticated downloads keep working.
 */
internal fun enqueueBrowserDownload(
    ctx: Context,
    url: String,
    contentDisposition: String?,
    mimeType: String?,
) {
    runCatching {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            mimeType?.let { setMimeType(it) }
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
        }
        ctx.getSystemService(DownloadManager::class.java)?.enqueue(request)
            ?: throw ActivityNotFoundException("no DownloadManager")
        Toast.makeText(ctx, ctx.getString(R.string.browser_download_started, fileName), Toast.LENGTH_SHORT).show()
    }.onFailure {
        Log.w("RikkaWebView", "download failed for $url", it)
        Toast.makeText(ctx, R.string.browser_download_failed, Toast.LENGTH_SHORT).show()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewHost(
    modifier: Modifier = Modifier,
    initialUrl: String,
    onWebViewReady: (WebView) -> Unit,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onLoadProgress: (Int) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCanGoForwardChange: (Boolean) -> Unit,
    onPageStarted: () -> Unit,
    onMainFrameError: (BrowserPageError) -> Unit,
    onSslError: (BrowserSslError) -> Unit,
    onLongPress: (BrowserHitTarget) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideCustomView: () -> Unit,
) {
    val ctx = LocalContext.current
    // Construct the WebView ONCE — `remember` survives recomp + (with key=Unit) survives
    // configuration changes (the Activity declares `configChanges` so the system never
    // recreates us). Recreating per-recomp would reset history, scroll, and JS state.
    val webView = remember {
        // Enable Chrome DevTools attachment ONLY in debug builds. In release, leaving
        // this on lets anyone with adb (lent phone, ADB-over-WiFi attacker) attach
        // chrome://inspect and read the WebView's cookies / localStorage / authenticated
        // session bodies. Gate behind BuildConfig.DEBUG — turning Chrome inspection on
        // in release is a privacy posture choice the user never consented to.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        WebView(ctx).apply {
            // WebView derives its CSS viewport height (100vh/100dvh/percentage heights)
            // from its LayoutParams, not from its actual measured/laid-out size. Without
            // this, the default WRAP_CONTENT makes vh/% heights resolve to 0 even though
            // Compose's AndroidView correctly sizes the view on screen (measured on
            // device: innerHeight=774 but 100vh=0). HeadlessBrowserSession already sets
            // its own fixed-size LayoutParams for the same reason; MATCH_PARENT here lets
            // the real Compose-driven size flow through instead.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            // Shared with HeadlessBrowserSession — every render-related setting
            // (mixedContentMode, hardware layer, autoplay, UA strip, file:// access)
            // lives in configureWebViewForRikka so foreground + headless behave
            // identically. See BrowserWebViewConfig.kt for the why.
            configureWebViewForRikka(this)

            // Profile dir is informational — the global WebView databases live where the
            // WebView wants. We create the dir ourselves in BrowserActivity.onCreate so
            // Pass 3's "Clear browsing data" has a stable target to wipe.
            File(ctx.filesDir, "browser-profile").apply { if (!exists()) mkdirs() }

            // The WebView has no download facility of its own — dead taps without this.
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                enqueueBrowserDownload(ctx, url, contentDisposition, mimeType)
            }

            // Long-press context menu for links and images (copy / download / external).
            setOnLongClickListener {
                val hit = hitTestResult
                val extra = hit?.extra
                if (hit != null && extra != null) {
                    when (hit.type) {
                        WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                            onLongPress(BrowserHitTarget(extra, isImage = false))
                            true
                        }
                        WebView.HitTestResult.IMAGE_TYPE,
                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                            onLongPress(BrowserHitTarget(extra, isImage = true))
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url ?: return false
                    val scheme = url.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") {
                        // file://: block page/JS-initiated navigation unless the current
                        // document is already file:// (skill webview cards moving between
                        // their own sub-pages). App-initiated loadUrl() bypasses this
                        // callback, so opening a skill card stays unaffected. Without this,
                        // a browsed page (or eval'd JS) could steer the WebView into
                        // app-private files and exfiltrate them via browser_get_text.
                        if (scheme == "file") {
                            return view?.url?.startsWith("file:", ignoreCase = true) != true
                        }
                        // about:/data:/javascript: — let the WebView handle them itself.
                        if (scheme == "about" || scheme == "data" || scheme == "javascript") {
                            return false
                        }
                        // tel:/mailto:/intent:/market:/weixin:/… — hand to the system;
                        // loading them into the WebView only yields a raw error page.
                        return routeExternalScheme(ctx, url.toString())
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    onPageStarted()
                    if (url != null) onUrlChange(url)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null) onUrlChange(url)
                    onCanGoBackChange(view?.canGoBack() == true)
                    onCanGoForwardChange(view?.canGoForward() == true)
                    // Adb-friendly white-page diagnostic. Tag = "RikkaWebView". Filter:
                    //   adb logcat -s RikkaWebView
                    // Dumps body innerText length + first 100 chars + meta viewport so
                    // you can tell at a glance whether the DOM is populated (render bug)
                    // or empty (load bug) without needing chrome://inspect.
                    view?.evaluateJavascript(
                        """
                        JSON.stringify({
                          rs: document.readyState,
                          docLen: (document.documentElement ? document.documentElement.outerHTML.length : 0),
                          bodyLen: (document.body ? document.body.innerText.length : 0),
                          children: (document.body ? document.body.children.length : 0),
                          firstText: (document.body ? document.body.innerText.slice(0, 100) : ''),
                          viewport: (function(){var m=document.querySelector('meta[name=viewport]');return m?m.content:'';})()
                        })
                        """.trimIndent(),
                    ) { json ->
                        Log.d("RikkaWebView", "onPageFinished $url -> $json")
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    val isMainFrame = request?.isForMainFrame == true
                    Log.w(
                        "RikkaWebView",
                        "onReceivedError mainFrame=$isMainFrame url=${request?.url} code=${error?.errorCode} desc=${error?.description}",
                    )
                    // Main-frame failures otherwise render as a silent white page.
                    // (about:blank errors are noise from teardown — ignore them.)
                    if (isMainFrame && request.url?.toString() != "about:blank") {
                        onMainFrameError(
                            BrowserPageError(
                                url = request.url?.toString().orEmpty(),
                                errorCode = error?.errorCode,
                                httpStatus = null,
                                description = error?.description?.toString(),
                            ),
                        )
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    // Sub-resource 404s (favicons, trackers) are constant noise — only the
                    // main frame is user-visible. Some servers answer 4xx/5xx with a fully
                    // rendered error page; covering that with our overlay is still the
                    // better UX (consistent, localized, actionable).
                    val status = errorResponse?.statusCode ?: 0
                    if (request?.isForMainFrame == true && status >= 400) {
                        onMainFrameError(
                            BrowserPageError(
                                url = request.url?.toString().orEmpty(),
                                errorCode = null,
                                httpStatus = status,
                                description = errorResponse?.reasonPhrase,
                            ),
                        )
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    // Default WebView behaviour is cancel (safe) but SILENT — the user just
                    // sees a stuck page. Surface a dialog; default action stays cancel.
                    if (handler == null) return
                    onSslError(
                        BrowserSslError(
                            url = error?.url ?: view?.url.orEmpty(),
                            description = error?.toString(),
                            handler = handler,
                        ),
                    )
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    onLoadProgress(newProgress)
                }
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (title != null) onTitleChange(title)
                }
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    // Surface page-side console.log / console.error to logcat so the user
                    // can debug white pages from a terminal without DevTools.
                    if (message != null) {
                        val priority = when (message.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                            ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
                            else -> Log.INFO
                        }
                        Log.println(
                            priority,
                            "RikkaWebViewConsole",
                            "${message.sourceId()}:${message.lineNumber()} ${message.message()}",
                        )
                    }
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    // Without this, <input type=file> buttons are dead taps.
                    if (filePathCallback == null || fileChooserParams == null) return false
                    onShowFileChooser(filePathCallback, fileChooserParams)
                    return true
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    // setSupportMultipleWindows(false) + no onCreateWindow = every
                    // target=_blank / window.open click is a dead tap. Transport trick:
                    // hand a throwaway WebView to the page, intercept its first load, and
                    // route the URL into the real WebView (single-window browser).
                    val parent = view ?: return false
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val temp = WebView(parent.context)
                    temp.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            parent.loadUrl(request.url.toString())
                            temp.destroy()
                            return true
                        }
                    }
                    transport.webView = temp
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    // Fullscreen video. Without this the fullscreen button is a dead tap.
                    if (view == null || callback == null) return
                    onShowCustomView(view, callback)
                }

                override fun onHideCustomView() {
                    onHideCustomView()
                }
            }

            loadUrl(initialUrl)
            onWebViewReady(this)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { webView },
    )
}
