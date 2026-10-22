package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope every screen-automation tool returns when the service is down. The whole point
 * of the two branches is the advice: telling a user to "turn the switch on" when it is already
 * on (and the ROM merely recycled the service) is what made this look like a dead end.
 */
class A11yNotActiveTest {

    @Test
    fun error_code_is_stable() {
        // The model has been taught this string for a while; keep it byte-for-byte.
        assertEquals(
            "AccessibilityService not active",
            A11yNotActive.envelope(false)["error"]!!.jsonPrimitive.content,
        )
        assertEquals(
            A11yNotActive.ERROR,
            A11yNotActive.envelope(true)["error"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun enabled_but_disconnected_reports_recycled_and_offers_repair() {
        val env = A11yNotActive.envelope(enabledInSettings = true)
        assertNotNull("a live toggle that is not bound needs a diagnosis", env["diagnosis"])
        val recovery = env["recovery"]!!.jsonPrimitive.content
        assertTrue(recovery.contains("Accessibility"))
        assertTrue("should offer the in-app repair path", recovery.contains("Shizuku"))
        assertTrue("should mention the power-management allow-list", recovery.contains("background"))
    }

    @Test
    fun never_enabled_points_at_the_system_toggle_only() {
        val env = A11yNotActive.envelope(enabledInSettings = false)
        assertNull("no root-cause guessing when the switch is simply off", env["diagnosis"])
        val recovery = env["recovery"]!!.jsonPrimitive.content
        assertTrue(recovery.contains("Settings → Accessibility"))
        assertFalse(
            "must not blame background killing when the user never turned it on",
            recovery.contains("killed in the background"),
        )
    }
}
