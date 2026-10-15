package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the virtual display tools' command builders and parameter validators.
 * These exercise the shell-command construction and parameter checking without needing
 * Android, Shizuku, or a device — the same pattern as the existing tool tests.
 */
class VirtualDisplayToolsTest {

    // --- validateCreateParams ----------------------------------------------------------

    @Test
    fun `valid create params return Triple`() {
        val result = validateCreateParams(1080, 1920, 320)
        assertEquals(Triple(1080, 1920, 320), result)
    }

    @Test
    fun `null density defaults to 320`() {
        val result = validateCreateParams(720, 1280, null)
        assertEquals(Triple(720, 1280, 320), result)
    }

    @Test
    fun `zero width returns null`() {
        assertNull(validateCreateParams(0, 1920, 320))
    }

    @Test
    fun `width exceeding 4096 returns null`() {
        assertNull(validateCreateParams(5000, 1920, 320))
    }

    @Test
    fun `zero height returns null`() {
        assertNull(validateCreateParams(1080, 0, 320))
    }

    @Test
    fun `density 0 returns null`() {
        assertNull(validateCreateParams(1080, 1920, 0))
    }

    @Test
    fun `density 641 returns null`() {
        assertNull(validateCreateParams(1080, 1920, 641))
    }

    @Test
    fun `null width returns null`() {
        assertNull(validateCreateParams(null, 1920, 320))
    }

    @Test
    fun `null height returns null`() {
        assertNull(validateCreateParams(1080, null, 320))
    }

    // --- buildLaunchCommand ------------------------------------------------------------

    @Test
    fun `launch command without activity`() {
        val cmd = buildLaunchCommand(42, "com.android.chrome", null)
        assertEquals("am start --display 42 -n com.android.chrome", cmd)
    }

    @Test
    fun `launch command with activity`() {
        val cmd = buildLaunchCommand(1, "com.example.app", "com.example.app.MainActivity")
        assertEquals(
            "am start --display 1 -n com.example.app/com.example.app.MainActivity",
            cmd,
        )
    }

    // --- buildTapCommand ----------------------------------------------------------------

    @Test
    fun `tap command format`() {
        assertEquals("input -d 5 tap 100 200", buildTapCommand(5, 100, 200))
    }

    @Test
    fun `tap command with zero coordinates`() {
        assertEquals("input -d 0 tap 0 0", buildTapCommand(0, 0, 0))
    }

    // --- buildSwipeCommand --------------------------------------------------------------

    @Test
    fun `swipe command without duration`() {
        val cmd = buildSwipeCommand(3, 100, 200, 300, 400, null)
        assertEquals("input -d 3 swipe 100 200 300 400", cmd)
    }

    @Test
    fun `swipe command with duration`() {
        val cmd = buildSwipeCommand(3, 100, 200, 300, 400, 500)
        assertEquals("input -d 3 swipe 100 200 300 400 500", cmd)
    }

    // --- buildKeyCommand ----------------------------------------------------------------

    @Test
    fun `key command format`() {
        assertEquals("input -d 2 keyevent 4", buildKeyCommand(2, 4))
    }

    @Test
    fun `key command with enter keycode`() {
        assertEquals("input -d 1 keyevent 66", buildKeyCommand(1, 66))
    }

    // --- buildTextCommand ---------------------------------------------------------------

    @Test
    fun `text command simple string`() {
        assertEquals("input -d 1 text 'hello'", buildTextCommand(1, "hello"))
    }

    @Test
    fun `text command with spaces`() {
        assertEquals(
            "input -d 2 text 'hello world'",
            buildTextCommand(2, "hello world"),
        )
    }

    @Test
    fun `text command escapes single quotes`() {
        // ' becomes '\'' in the output
        val cmd = buildTextCommand(1, "it's")
        assertEquals("input -d 1 text 'it'\\''s'", cmd)
    }

    @Test
    fun `text command with special chars`() {
        assertEquals(
            "input -d 1 text 'a&b|c'",
            buildTextCommand(1, "a&b|c"),
        )
    }

    @Test
    fun `text command empty string`() {
        assertEquals("input -d 1 text ''", buildTextCommand(1, ""))
    }

    @Test
    fun `text command with newlines`() {
        // The text command should handle newlines as-is (the shell single-quote preserves them)
        val cmd = buildTextCommand(1, "line1\nline2")
        assertTrue(cmd.contains("input -d 1 text"))
        assertTrue(cmd.contains("line1"))
        assertTrue(cmd.contains("line2"))
    }
}
