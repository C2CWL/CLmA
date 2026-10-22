package me.rerere.rikkahub.data.ai.tools.local

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.service.RikkaAccessibilityService

/**
 * Bridge between the LLM tool factories and the live AccessibilityService.
 * The service publishes itself via [RikkaAccessibilityService.instance]; this object
 * wraps the access pattern in a uniform "service-not-active" envelope so every tool
 * gets the same recovery hint.
 */
object AccessibilityServiceHandle {

    /**
     * Application context, set once from `RikkaHubApp.onCreate`. Only used so the
     * service-not-active envelope can tell "the user never enabled it" apart from "the system
     * dropped it in the background" — the tool factories have no Context of their own.
     */
    @Volatile
    private var appContext: Context? = null

    /** Called once from Application.onCreate. Idempotent. */
    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /** True iff our component is listed in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES. */
    fun isEnabledInSettings(ctx: Context): Boolean {
        val expected = ComponentName(ctx, RikkaAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    /** True iff the service has connected and the singleton is live. */
    fun isRunning(): Boolean = RikkaAccessibilityService.instance != null

    /**
     * Runs [block] with the live service if it's connected; otherwise returns the standard
     * service-not-active envelope as a JsonObject. Tools should wrap their entire execute()
     * body in this call.
     */
    suspend fun withService(
        block: suspend (RikkaAccessibilityService) -> JsonObject
    ): JsonObject {
        val svc = RikkaAccessibilityService.instance
            ?: return notActiveEnvelope()
        return block(svc)
    }

    /**
     * The envelope a tool returns when the service is not live. When we can read the system
     * setting, an entry that is present means the service was almost certainly recycled
     * (the ROM killed the app), which needs a different recovery hint than "never enabled".
     */
    fun notActiveEnvelope(): JsonObject {
        val ctx = appContext
        val enabledInSettings = ctx != null && isEnabledInSettings(ctx)
        return A11yNotActive.envelope(enabledInSettings)
    }
}
