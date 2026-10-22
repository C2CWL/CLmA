package me.rerere.rikkahub.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pure decision + command-building rules for running the in-app browser in the background
 * (off the user's foreground display) so the agent can drive web pages without stealing the
 * screen.
 *
 * Two substrates, chosen by [decideBackgroundHost]:
 *  - [BackgroundHostKind.VIRTUAL_DISPLAY]: launch [BrowserActivity] onto a virtual display
 *    via `am start --display` (shell/​Shizuku). The real hardware-composited display keeps
 *    the WebView rendering identically to the foreground path, and the existing 17 browser
 *    tools drive it unchanged (BrowserController's bind is display-agnostic).
 *  - [BackgroundHostKind.HEADLESS]: fall back to the off-screen WebView session already used
 *    by Telegram / cron / sub-agent calls — no Shizuku required, screenshots stream/draw
 *    into the calling chat.
 *
 * The VD path is preferred when Shizuku is ready; the headless path is the universal
 * fallback so a missing Shizuku never makes "background browsing" disappear. When neither
 * is viable (no Shizuku AND no calling conversation to stream into) the caller surfaces a
 * clean error and the model can fall back to an explicit foreground open.
 *
 * Pure Kotlin (only kotlinx.serialization for the exec-result probe) → JVM-testable.
 */
enum class BackgroundHostKind { VIRTUAL_DISPLAY, HEADLESS }

/**
 * Pick the background substrate.
 *  - Shizuku ready → [BackgroundHostKind.VIRTUAL_DISPLAY] (best fidelity).
 *  - else, a conversation is available to host the headless session → [BackgroundHostKind.HEADLESS].
 *  - else → null (caller reports background_unavailable).
 */
fun decideBackgroundHost(shizukuReady: Boolean, hasConversation: Boolean): BackgroundHostKind? = when {
    shizukuReady -> BackgroundHostKind.VIRTUAL_DISPLAY
    hasConversation -> BackgroundHostKind.HEADLESS
    else -> null
}

/**
 * POSIX single-quote escape for splicing arbitrary strings (URLs, conversation ids) into a
 * shell `am start --es` argument. Wraps in single quotes and escapes embedded single quotes
 * via the `'…'\''…'` idiom — the only form `am`'s argument parser honours reliably.
 */
fun shellQuoteSingle(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Build the `am start --display <id>` command that launches [BrowserActivity] onto a virtual
 * display, threading the initial URL and companion conversation id as string extras exactly
 * as [BrowserActivity.intent] would for the foreground path.
 *
 * [packageName] is the runtime application id (Context.getPackageName) — the activity class
 * name is fixed (`me.rerere.rikkahub.browser.BrowserActivity`) regardless of flavor/brand.
 */
fun buildBrowserLaunchCommand(
    packageName: String,
    displayId: Int,
    url: String?,
    conversationId: String?,
): String = buildString {
    append("am start -n ")
    append(packageName)
    append("/me.rerere.rikkahub.browser.BrowserActivity")
    append(" --display ")
    append(displayId)
    // Shell-launched activities get NEW_TASK implicitly; make it explicit for clarity.
    append(" -f 268435456") // Intent.FLAG_ACTIVITY_NEW_TASK (0x10000000)
    if (!url.isNullOrBlank()) {
        append(" --es me.rerere.rikkahub.browser.EXTRA_INITIAL_URL ")
        append(shellQuoteSingle(url))
    }
    if (!conversationId.isNullOrBlank()) {
        append(" --es me.rerere.rikkahub.browser.EXTRA_CONVERSATION_ID ")
        append(shellQuoteSingle(conversationId))
    }
}

/**
 * Probe a [ShizukuManager.exec] result envelope for success: no top-level `error` key and
 * `exit_code == 0`. `am start` exits non-zero on a bad component/display and prints the
 * reason to stderr (surfaced in the envelope by the user service), so this is a reliable
 * signal without parsing am's stdout.
 */
fun amCommandSucceeded(execResult: JsonObject): Boolean {
    if (execResult["error"] != null) return false
    val exit = execResult["exit_code"]?.jsonPrimitive?.intOrNull
    return exit == 0
}

/**
 * Error envelope returned to the model when background browsing is requested but neither
 * substrate is viable (no Shizuku and no conversation). Honest failure with a recovery hint
 * rather than silently falling back to the foreground the user explicitly wanted to avoid.
 */
fun backgroundUnavailableEnvelope(): JsonObject = buildJsonObject {
    put("error", "background_unavailable")
    put(
        "recovery",
        "Background browsing needs either Shizuku (for a virtual display) or a calling " +
            "conversation (for a headless session). Enable Shizuku in Settings → Shizuku, " +
            "or retry browser_open without background=true to open in the foreground.",
    )
}
