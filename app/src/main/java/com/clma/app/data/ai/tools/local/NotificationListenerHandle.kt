package com.clma.app.data.ai.tools.local

import android.content.Context
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.clma.app.service.RikkaNotificationListenerService
import com.clma.app.service.LegacyComponentNames

/**
 * Bridge between the LLM tool factories and the live notification listener service.
 * Mirrors AccessibilityServiceHandle: the service publishes itself via instance,
 * we wrap access in a uniform service-not-bound envelope.
 */
object NotificationListenerHandle {

    /** True iff our component is listed in Settings.Secure.ENABLED_NOTIFICATION_LISTENERS. */
    fun isEnabledInSettings(ctx: Context): Boolean {
        val expected = LegacyComponentNames.notificationListener(ctx).flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    /** True iff the service is currently bound by the OS. */
    fun isBound(): Boolean = RikkaNotificationListenerService.instance != null

    /**
     * Run [block] with the bound listener. Returns the standard not-bound envelope when the
     * service is unbound (user disabled it, OS unbound it under memory pressure, etc.).
     */
    suspend fun withListener(
        block: suspend (RikkaNotificationListenerService) -> JsonObject
    ): JsonObject {
        val svc = RikkaNotificationListenerService.instance ?: return notBoundEnvelope()
        return block(svc)
    }

    fun notBoundEnvelope(): JsonObject = buildJsonObject {
        put("error", "notification_listener_not_bound")
        put("recovery", "Enable CLmA in Settings → Notification access. Then return to the app.")
    }
}
