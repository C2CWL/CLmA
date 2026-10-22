package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The JSON envelope every screen-automation tool returns when the AccessibilityService is not
 * connected. Pure (kotlinx-serialization only) so it is unit-testable on the JVM without an
 * Android context; [AccessibilityServiceHandle] supplies the one OS fact it needs.
 *
 * The two causes look identical to a tool but need completely different advice, so
 * [enabledInSettings] splits them:
 * - `false` ⇒ the service is not (or no longer) enabled at all: point at the system toggle.
 * - `true`  ⇒ the toggle IS on but the service is not bound. On aggressive OEM ROMs
 *   (Vivo/Xiaomi/Huawei) that is the signature of "the app was killed in the background and
 *   the service was dropped with it", so also hand over the keep-alive steps and the one-tap
 *   repair instead of telling the user to redo a toggle that is already on.
 */
object A11yNotActive {
    const val ERROR = "AccessibilityService not active"

    fun envelope(enabledInSettings: Boolean): JsonObject = buildJsonObject {
        put("error", ERROR)
        if (enabledInSettings) {
            put(
                "diagnosis",
                "The system switch is ON but the service is not connected. This usually means " +
                    "the app was killed in the background (common on Vivo/Xiaomi/Huawei with " +
                    "aggressive power saving) and the accessibility service was dropped with it.",
            )
            put(
                "recovery",
                "Re-enable it: Settings → Accessibility → RikkaHub (or use the 'One-tap repair " +
                    "(Shizuku)' button on the in-app 设置 → 无障碍 page). If it keeps dropping, " +
                    "allow-list RikkaHub against the system's power management: autostart + allow " +
                    "background battery use + lock it in the recents list.",
            )
        } else {
            put(
                "recovery",
                "Enable RikkaHub in Settings → Accessibility → Installed Apps",
            )
        }
    }
}
