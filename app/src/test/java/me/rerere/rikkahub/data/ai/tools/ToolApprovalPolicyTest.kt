package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blacklist-era approval decision, exercised as a pure function so the whole matrix runs on
 * a bare JVM. Ordering matters and each branch gets its own case.
 */
class ToolApprovalPolicyTest {

    private fun decide(
        toolName: String,
        blacklist: Set<String> = emptySet(),
        yolo: Boolean = false,
        headless: Boolean = false,
        allowedForChat: Boolean = false,
    ): Boolean = ToolApprovalPolicy.shouldAutoApprove(
        toolName = toolName,
        blacklist = blacklist,
        yolo = yolo,
        headless = headless,
        allowedForChat = allowedForChat,
    )

    // --- default: nothing asks ---

    @Test
    fun `empty blacklist auto-approves an ordinary tool`() {
        assertTrue(decide("read_file"))
        assertTrue(decide("termux_run_command"))
    }

    // --- blacklist ---

    @Test
    fun `a blacklisted tool does not auto-approve`() {
        assertFalse(decide("termux_run_command", blacklist = setOf("termux_run_command")))
    }

    @Test
    fun `the blacklist only affects the named tool`() {
        val blacklist = setOf("termux_run_command")
        assertFalse(decide("termux_run_command", blacklist = blacklist))
        assertTrue(decide("ssh_exec", blacklist = blacklist))
    }

    // --- hard floor ---

    @Test
    fun `the floor asks even when it is not on the blacklist`() {
        assertFalse(decide("eval_javascript"))
        assertFalse(decide("grant_directory_access"))
    }

    @Test
    fun `opaque mcp tools ask regardless of the blacklist`() {
        assertFalse(decide("mcp__server__tool"))
    }

    // --- "allow for this chat" ---

    @Test
    fun `a chat grant suppresses a blacklisted tool's prompt`() {
        assertTrue(
            decide("termux_run_command", blacklist = setOf("termux_run_command"), allowedForChat = true)
        )
    }

    @Test
    fun `a chat grant cannot waive the floor`() {
        assertFalse(decide("eval_javascript", allowedForChat = true))
        assertFalse(decide("mcp__server__tool", allowedForChat = true))
    }

    // --- yolo ---

    @Test
    fun `yolo auto-approves the blacklist and the floor`() {
        assertTrue(decide("termux_run_command", blacklist = setOf("termux_run_command"), yolo = true))
        assertTrue(decide("eval_javascript", yolo = true))
        assertTrue(decide("mcp__server__tool", yolo = true))
    }

    // --- headless ---

    @Test
    fun `a headless run auto-approves everything so the turn cannot hang`() {
        assertTrue(decide("termux_run_command", blacklist = setOf("termux_run_command"), headless = true))
        assertTrue(decide("eval_javascript", headless = true))
    }

    // --- ask_user ---

    @Test
    fun `ask_user pauses on an attended surface but not headless`() {
        assertFalse(decide("ask_user"))
        assertTrue(decide("ask_user", headless = true))
    }

    @Test
    fun `ask_user ignores yolo so its card still reaches the user`() {
        assertFalse(decide("ask_user", yolo = true))
    }
}
