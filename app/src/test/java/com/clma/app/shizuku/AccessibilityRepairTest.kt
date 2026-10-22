package me.rerere.rikkahub.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [mergeEnabledAccessibilityServices] and [buildAccessibilityRepairCommand]: the
 * pure half of Settings -> Accessibility -> "one-tap repair". The command itself can only be
 * exercised on a device with a running Shizuku, see [ShizukuManager]; what is pinned down here
 * is the list arithmetic and the shape of the script (both write paths, the ROM workaround).
 */
class AccessibilityRepairTest {

    private val comp = "excp.rikkahub.debug/me.rerere.rikkahub.service.RikkaAccessibilityService"

    @Test
    fun `an unset list - null, blank or the string null - becomes just our component`() {
        assertEquals(comp, mergeEnabledAccessibilityServices(null, comp))
        assertEquals(comp, mergeEnabledAccessibilityServices("", comp))
        assertEquals(comp, mergeEnabledAccessibilityServices("   ", comp))
        // `settings get` prints the literal string "null" for a key that was never set.
        assertEquals(comp, mergeEnabledAccessibilityServices("null", comp))
    }

    @Test
    fun `other services are kept and ours is appended`() {
        val other = "other.app/other.app.Service"
        assertEquals("$other:$comp", mergeEnabledAccessibilityServices(other, comp))
    }

    @Test
    fun `an already-enabled component is left alone, order preserved`() {
        val other = "other.app/other.app.Service"
        assertEquals(comp, mergeEnabledAccessibilityServices(comp, comp))
        assertEquals("$other:$comp", mergeEnabledAccessibilityServices("$other:$comp", comp))
        assertEquals("$comp:$other", mergeEnabledAccessibilityServices("$comp:$other", comp))
    }

    @Test
    fun `an existing entry matches case-insensitively`() {
        val shouty = comp.uppercase()
        assertEquals(shouty, mergeEnabledAccessibilityServices(shouty, comp))
    }

    @Test
    fun `the command reads the list first and keeps every other entry`() {
        val command = buildAccessibilityRepairCommand(comp)
        assertTrue(command, command.contains("settings get secure enabled_accessibility_services"))
        assertTrue(command, command.contains("else cur=\"\$cur:${comp}\"; fi"))
        assertTrue(command, command.contains("accessibility_enabled"))
    }

    @Test
    fun `the command carries the vivo fallback and the stale-settings-process kill`() {
        val command = buildAccessibilityRepairCommand(comp)
        // `settings put` is silently dropped on some OEM builds, `content insert` is not.
        assertTrue(command, command.contains("settings put secure enabled_accessibility_services"))
        assertTrue(
            command,
            command.contains(
                "content insert --uri content://settings/secure " +
                    "--bind name:s:enabled_accessibility_services"
            )
        )
        // Without this the Settings process rewrites its stale copy over ours.
        assertTrue(command, command.contains("am force-stop com.android.settings"))
    }
}
