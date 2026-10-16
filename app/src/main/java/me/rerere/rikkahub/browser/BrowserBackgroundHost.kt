package me.rerere.rikkahub.browser

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.local.VirtualDisplayController
import me.rerere.rikkahub.shizuku.ShizukuManager

/**
 * Android-side glue for background browsing: ensure a dedicated virtual display exists for
 * the browser and launch [BrowserActivity] onto it via Shizuku (`am start --display`).
 *
 * The display is process-owned (created via the public [VirtualDisplayController], no
 * Shizuku needed for creation) and reused across browser tasks; if it has been destroyed
 * (e.g. the user ran `virtual_display_destroy`) the next [launch] recreates it. The pure
 * decision/command rules live in [BrowserBackgroundRules] so they stay JVM-testable.
 *
 * Thread-safety: [browserDisplayId] is read/written under [lock]. [launch] is suspend and
 * hops to IO for the Shizuku call.
 */
object BrowserBackgroundHost {

    private const val TAG = "BrowserBgHost"
    private const val SHELL_TIMEOUT_MS = 10_000

    private val lock = Any()

    /** The display id currently dedicated to the browser, or null if not yet created. */
    @Volatile
    private var browserDisplayId: Int? = null

    /** True iff Shizuku's binder is alive AND the app holds the shell permission. */
    fun shizukuReady(): Boolean =
        ShizukuManager.isBinderAlive() && ShizukuManager.isPermissionGranted()

    /**
     * Launch the browser in the background onto a (reused) virtual display.
     *
     * Returns the [am start][ShizukuManager.exec] result envelope and the display id used.
     * The caller ([browserOpenTool]) checks [amCommandSucceeded] and then
     * [BrowserController.awaitBind] before reporting success — a green am exit just means
     * the Activity start intent was accepted, not that onCreate has published its WebView.
     */
    suspend fun launch(
        context: Context,
        url: String?,
        conversationId: String?,
    ): Pair<JsonObject, Int?> = withContext(Dispatchers.IO) {
        val displayId = ensureDisplay(context)
        if (displayId == null) {
            return@withContext buildJsonObjectFailure("virtual_display_create_failed") to null
        }
        val cmd = buildBrowserLaunchCommand(context.packageName, displayId, url, conversationId)
        Log.i(TAG, "launching background browser on display $displayId: $cmd")
        val result = ShizukuManager.exec(context, cmd, SHELL_TIMEOUT_MS)
        result to displayId
    }

    /**
     * Get-or-create the browser's dedicated virtual display, sized to the default display's
     * real metrics so pages render at a believable phone viewport. Returns null on failure.
     */
    private suspend fun ensureDisplay(context: Context): Int? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = browserDisplayId
            if (current != null && VirtualDisplayController.contains(current)) {
                return@withContext current
            }
        }
        val (w, h, dpi) = defaultDisplaySize(context) ?: return@withContext null
        val result = VirtualDisplayController.create(context, w, h, dpi)
        when (result) {
            is VirtualDisplayController.Result.Ok -> {
                synchronized(lock) { browserDisplayId = result.value }
                Log.i(TAG, "created browser virtual display id=${result.value} ${w}x${h} dpi=$dpi")
                result.value
            }
            is VirtualDisplayController.Result.Error -> {
                Log.e(TAG, "virtual display create failed: ${result.code} ${result.message}")
                null
            }
        }
    }

    /** Best-effort read of the default display's real metrics; null if unavailable. */
    private fun defaultDisplaySize(context: Context): Triple<Int, Int, Int>? {
        return runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching null
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            // Clamp to a sane max so a huge display doesn't allocate an oversized surface;
            // 1080 wide is plenty for a background browser viewport.
            val w = metrics.widthPixels.coerceAtMost(1080)
            val h = metrics.heightPixels.coerceAtMost(2400)
            Triple(w, h, metrics.densityDpi.coerceIn(160, 640))
        }.getOrNull()
    }
}

/** Trivial JsonObject builder for an error envelope (kept local to avoid import noise). */
private fun buildJsonObjectFailure(code: String): JsonObject = buildJsonObject {
    put("error", code)
}
