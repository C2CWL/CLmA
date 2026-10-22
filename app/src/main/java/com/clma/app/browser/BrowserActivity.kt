package me.rerere.rikkahub.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.koin.android.ext.android.inject
import java.io.File

/**
 * Visible foreground browser the LLM drives via the 17 browser tools (Pass 2).
 *
 * Pass 1 ships the shell: WebView + address bar + AI stripe. No tools yet — the user
 * can launch via Settings → Browser → "Open browser" (or, in Pass 2+, via the AI's
 * `browser_open` tool) and see an empty about:blank with the Compose chrome.
 *
 * **Lifecycle contract.** This Activity is the sole owner of its WebView. It binds
 * itself into [BrowserController] in onCreate and unbinds in onDestroy. The controller
 * holds only a [java.lang.ref.WeakReference] so the GC can reclaim the WebView even if
 * a stale tool call leaks the reference past finish().
 *
 * **Profile dir.** Cookies + localStorage live in `${filesDir}/browser-profile/` so
 * they survive process death and app updates. The dir is created on first launch.
 * Pass 3 will register an exclusion in `backup_rules.xml` to avoid restoring auth
 * cookies onto a fresh device.
 */
@OptIn(ExperimentalUuidApi::class)
class BrowserActivity : ComponentActivity() {

    private var webView: WebView? = null
    private val canGoBack = mutableStateOf(false)
    private val canGoForward = mutableStateOf(false)
    private val currentUrl = mutableStateOf("about:blank")
    private val currentTitle = mutableStateOf("")
    private val loadProgress = mutableStateOf(0)
    private val searchEngineId = mutableStateOf(BrowserSearchEngines.DEFAULT.id)
    private val desktopMode = mutableStateOf(false)
    private var defaultUserAgent: String? = null
    private var conversationId: Uuid? = null

    private val browserPreferences: BrowserPreferences by inject()

    // Fullscreen video (WebChromeClient.onShowCustomView) — the custom view is attached
    // above the whole Compose content and system bars are hidden until onHideCustomView.
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // <input type=file> bridge: the WebChromeClient callback is parked here while the
    // system picker is open, then resolved with the picked URIs (or null on cancel).
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            callback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Optional companion conversation. browser_open passes the calling chat's id so the
        // mini-chat overlay can drive the same chat — user watches the AI work and can
        // queue follow-up prompts without leaving the activity.
        conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }

        // Address-bar search engine (Settings → Browser). Collected for the Activity's
        // lifetime; the address bar normalises queries through it on every submit.
        lifecycleScope.launch {
            browserPreferences.searchEngineFlow().collect { searchEngineId.value = it }
        }

        // Best-effort profile dir creation. The WebView falls back to its default location if
        // creation fails — never a crash-on-launch risk. Pass 3's "Clear browsing data" will
        // also wipe + recreate this dir.
        val profileDir = File(filesDir, "browser-profile")
        if (!profileDir.exists()) {
            profileDir.mkdirs()
        }

        // Global cookie store config — applies to every WebView in the process. Spec calls
        // for third-party cookies on so the AI can navigate sites that auth across domains.
        CookieManager.getInstance().setAcceptCookie(true)

        // Hardware back: fullscreen video exits first, then WebView history, then finish.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    hideCustomView()
                    return
                }
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
        })

        setContent {
            RikkahubTheme {
                BrowserView(
                    onWebViewReady = { wv ->
                        // The Activity itself owns the WebView; stash it for back-press
                        // handling and bind into the global controller for tool dispatch.
                        webView = wv
                        BrowserController.bind(wv)
                        // Third-party cookies must be enabled per-WebView, not on the
                        // CookieManager singleton — the API split is a holdover from Lollipop.
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    },
                    onUrlChange = { currentUrl.value = it },
                    onTitleChange = { currentTitle.value = it },
                    onLoadProgress = { loadProgress.value = it },
                    onCanGoBackChange = { canGoBack.value = it },
                    onCanGoForwardChange = { canGoForward.value = it },
                    canGoBackState = canGoBack,
                    canGoForwardState = canGoForward,
                    currentUrlState = currentUrl,
                    currentTitleState = currentTitle,
                    loadProgressState = loadProgress,
                    onClose = { finish() },
                    onBackTap = { webView?.takeIf { it.canGoBack() }?.goBack() },
                    onForwardTap = { webView?.takeIf { it.canGoForward() }?.goForward() },
                    onRefreshTap = { webView?.reload() },
                    onStopAi = { BrowserController.stopCurrentTask() },
                    onNavigate = { normalized ->
                        // Already normalised by BrowserView with the current search engine.
                        webView?.loadUrl(normalized)
                    },
                    searchEngineId = searchEngineId.value,
                    desktopMode = desktopMode.value,
                    onDesktopModeChange = { enabled -> setDesktopMode(enabled) },
                    onShowFileChooser = { callback, params -> showFileChooser(callback, params) },
                    onShowCustomView = { view, callback -> showCustomView(view, callback) },
                    onHideCustomView = { hideCustomView() },
                    initialUrl = intent?.getStringExtra(EXTRA_INITIAL_URL) ?: "about:blank",
                    conversationId = conversationId,
                )
            }
        }
    }

    private fun showFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ) {
        // A stale callback (picker reopened without finishing) must be cancelled first —
        // WebView blocks further file inputs while one is outstanding.
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback
        runCatching { pickFiles.launch(params.createIntent()) }
            .onFailure {
                filePathCallback = null
                callback.onReceiveValue(null)
            }
    }

    private fun setDesktopMode(enabled: Boolean) {
        desktopMode.value = enabled
        val wv = webView ?: return
        val defaultUa = defaultUserAgent
            ?: wv.settings.userAgentString.also { defaultUserAgent = it }
        wv.settings.userAgentString = if (enabled) DESKTOP_USER_AGENT else defaultUa
        wv.reload()
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            // Already fullscreen — tell the new requester to go away (WebView contract).
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        (window.decorView as ViewGroup).addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun hideCustomView() {
        val view = customView ?: return
        (window.decorView as ViewGroup).removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        // Tear down the WebView the Activity created — not doing so leaks the entire view
        // hierarchy plus a JS engine on every Activity finish. Order matters: clear from the
        // controller first so an in-flight tool dispatch can fail-fast on browser_not_open
        // rather than racing the destroy.
        webView?.let { wv ->
            BrowserController.unbind(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        /** Optional extra: initial URL to navigate to on launch. Defaults to about:blank. */
        const val EXTRA_INITIAL_URL = "me.rerere.rikkahub.browser.EXTRA_INITIAL_URL"

        /**
         * Optional extra: companion conversation id (Uuid string). When set, the mini-chat
         * overlay sends prompts into this conversation so the user can keep talking to the
         * AI while watching the WebView. browser_open passes the caller's id; manual
         * launches from Settings can omit this and the overlay self-hides.
         */
        const val EXTRA_CONVERSATION_ID = "me.rerere.rikkahub.browser.EXTRA_CONVERSATION_ID"

        /**
         * Desktop-mode UA (menu toggle). A desktop Chrome string is enough for the vast
         * majority of UA-sniffing sites; the original UA is restored when toggled off.
         */
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.6478.122 Safari/537.36"

        /**
         * Builds the launch Intent. Used by the `browser_open` tool, by the skill webview
         * card, and by Settings → Browser → "Open browser".
         */
        fun intent(
            context: android.content.Context,
            url: String? = null,
            conversationId: String? = null,
        ): Intent =
            Intent(context, BrowserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (url != null) putExtra(EXTRA_INITIAL_URL, url)
                if (conversationId != null) putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
    }
}
