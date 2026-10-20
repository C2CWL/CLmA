package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `launch command without activity resolves launcher intent`() {
        // `-n <pkg>` is not a valid component ("Bad component name"), so a package-only
        // launch must use an implicit MAIN/LAUNCHER intent scoped to the package.
        val cmd = buildLaunchCommand(42, "com.android.chrome", null)
        assertEquals(
            "am start --display 42 -f 0x18000000 " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-p com.android.chrome",
            cmd,
        )
    }

    @Test
    fun `launch command with activity`() {
        val cmd = buildLaunchCommand(1, "com.example.app", "com.example.app.MainActivity")
        assertEquals(
            "am start --display 1 -f 0x18000000 -n com.example.app/com.example.app.MainActivity",
            cmd,
        )
    }

    @Test
    fun `launch command with relative activity`() {
        val cmd = buildLaunchCommand(7, "com.example.app", ".MainActivity")
        assertEquals(
            "am start --display 7 -f 0x18000000 -n com.example.app/.MainActivity",
            cmd,
        )
    }

    @Test
    fun `launch command always carries new-task and multiple-task flags`() {
        // --display is only honoured for a *new* task; without MULTIPLE_TASK the intent is
        // delivered to the app's existing instance and the task ends up on the virtual
        // display paused / not visible.
        assertTrue(buildLaunchCommand(3, "com.example.app", null).contains("-f 0x18000000"))
        assertTrue(buildLaunchCommand(3, "com.example.app", ".Main").contains("-f 0x18000000"))
    }

    // --- launch retry helpers -----------------------------------------------------------

    @Test
    fun `delivered-to-running-instance is detected`() {
        val out = "Starting: Intent { cmp=a/b }\n" +
            "Warning: Activity not started, intent has been delivered to currently " +
            "running top-most instance.\n"
        assertTrue(wasDeliveredToRunningInstance(out))
    }

    @Test
    fun `delivered-to-running-instance shorter variant is detected`() {
        assertTrue(wasDeliveredToRunningInstance("intent has been delivered to top-most instance"))
    }

    @Test
    fun `successful start is not treated as delivered-to-running-instance`() {
        assertFalse(wasDeliveredToRunningInstance("Starting: Intent { cmp=a/b }\n"))
    }

    @Test
    fun `unresolvable intent is not treated as delivered-to-running-instance`() {
        // "Activity not started, unable to resolve Intent" is a different failure — retrying
        // after force-stop would not help, so it must not trigger the retry path.
        val out = "Error: Activity not started, unable to resolve Intent { act=MAIN pkg=x }\n"
        assertFalse(wasDeliveredToRunningInstance(out))
    }

    @Test
    fun `force stop command format`() {
        assertEquals("am force-stop com.example.app", buildForceStopCommand("com.example.app"))
    }

    // --- parameter validation -----------------------------------------------------------

    @Test
    fun `valid package names are accepted`() {
        assertTrue(isValidPackageName("com.android.settings"))
        assertTrue(isValidPackageName("excp.rikkahub.debug"))
        assertTrue(isValidPackageName("a"))
    }

    @Test
    fun `package names with shell metacharacters are rejected`() {
        assertFalse(isValidPackageName("com.foo; rm -rf /"))
        assertFalse(isValidPackageName("com.foo && reboot"))
        assertFalse(isValidPackageName("com.foo bar"))
        assertFalse(isValidPackageName("com.foo\$(id)"))
        assertFalse(isValidPackageName(""))
        assertFalse(isValidPackageName(null))
    }

    @Test
    fun `activity names are validated`() {
        assertTrue(isValidActivityName(null))
        assertTrue(isValidActivityName(".MainActivity"))
        assertTrue(isValidActivityName("com.example.app.MainActivity"))
        assertTrue(isValidActivityName("com.example.app.MainActivity\$Inner"))
        assertFalse(isValidActivityName("pkg/Act"))
        assertFalse(isValidActivityName("Main; rm -rf /"))
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
