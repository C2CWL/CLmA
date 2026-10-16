package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mode-era approval decision, exercised as a pure function so the whole matrix runs on
 * a bare JVM. Ordering matters and each branch gets its own case.
 *
 * ★拍板①: Auto does NOT waive the floor — this is the deliberate semantic break from the
 * retired YOLO toggle, which sat ABOVE the floor. Here the floor is checked before the mode.
 */
class ToolApprovalPolicyTest {

    private fun decide(
        toolName: String,
        blacklist: Set<String> = emptySet(),
        mode: ApprovalMode = ApprovalMode.SEMI,
        headless: Boolean = false,
        allowedForChat: Boolean = false,
    ): Boolean = ToolApprovalPolicy.shouldAutoApprove(
        toolName = toolName,
        blacklist = blacklist,
        mode = mode,
        headless = headless,
        allowedForChat = allowedForChat,
    )

    // --- AUTO mode ---

    @Test
    fun `auto approves an ordinary tool`() {
        assertTrue(decide("read_file", mode = ApprovalMode.AUTO))
    }

    @Test
    fun `auto ignores the blacklist`() {
        assertTrue(decide("termux_run_command", blacklist = setOf("termux_run_command"), mode = ApprovalMode.AUTO))
    }

    @Test
    fun `auto does NOT waive the floor`() {
        assertFalse(decide("eval_javascript", mode = ApprovalMode.AUTO))
        assertFalse(decide("grant_directory_access", mode = ApprovalMode.AUTO))
        assertFalse(decide("mcp_add", mode = ApprovalMode.AUTO))
    }

    @Test
    fun `auto does NOT waive mcp tools`() {
        assertFalse(decide("mcp__server__tool", mode = ApprovalMode.AUTO))
    }

    // --- SEMI mode (blacklist) ---

    @Test
    fun `semi auto-approves an ordinary tool`() {
        assertTrue(decide("read_file", mode = ApprovalMode.SEMI))
    }

    @Test
    fun `semi asks for a blacklisted tool`() {
        assertFalse(decide("termux_run_command", blacklist = setOf("termux_run_command"), mode = ApprovalMode.SEMI))
    }

    @Test
    fun `semi only affects the named tool`() {
        val blacklist = setOf("termux_run_command")
        assertFalse(decide("termux_run_command", blacklist = blacklist, mode = ApprovalMode.SEMI))
        assertTrue(decide("ssh_exec", blacklist = blacklist, mode = ApprovalMode.SEMI))
    }

    // --- MANUAL mode ---

    @Test
    fun `manual asks for an ordinary tool`() {
        assertFalse(decide("read_file", mode = ApprovalMode.MANUAL))
    }

    @Test
    fun `manual asks for a blacklisted tool`() {
        assertFalse(decide("termux_run_command", blacklist = setOf("termux_run_command"), mode = ApprovalMode.MANUAL))
    }

    @Test
    fun `manual allows a chat-granted tool`() {
        assertTrue(decide("read_file", mode = ApprovalMode.MANUAL, allowedForChat = true))
    }

    // --- floor (applies to every mode) ---

    @Test
    fun `floor asks in semi even when not on blacklist`() {
        assertFalse(decide("eval_javascript", mode = ApprovalMode.SEMI))
        assertFalse(decide("grant_directory_access", mode = ApprovalMode.SEMI))
    }

    @Test
    fun `floor asks in manual`() {
        assertFalse(decide("eval_javascript", mode = ApprovalMode.MANUAL))
    }

    @Test
    fun `opaque mcp tools ask regardless of mode`() {
        assertFalse(decide("mcp__server__tool", mode = ApprovalMode.AUTO))
        assertFalse(decide("mcp__server__tool", mode = ApprovalMode.SEMI))
        assertFalse(decide("mcp__server__tool", mode = ApprovalMode.MANUAL))
    }

    // --- "allow for this chat" ---

    @Test
    fun `a chat grant suppresses a blacklisted tool in semi`() {
        assertTrue(
            decide("termux_run_command", blacklist = setOf("termux_run_command"), mode = ApprovalMode.SEMI, allowedForChat = true)
        )
    }

    @Test
    fun `a chat grant cannot waive the floor`() {
        assertFalse(decide("eval_javascript", allowedForChat = true, mode = ApprovalMode.AUTO))
        assertFalse(decide("mcp__server__tool", allowedForChat = true, mode = ApprovalMode.MANUAL))
    }

    // --- headless (no approval channel) ---

    @Test
    fun `headless auto-approves everything including the floor`() {
        assertTrue(decide("eval_javascript", headless = true, mode = ApprovalMode.MANUAL))
        assertTrue(decide("mcp__server__tool", headless = true, mode = ApprovalMode.SEMI))
    }

    @Test
    fun `headless overrides manual`() {
        assertTrue(decide("read_file", headless = true, mode = ApprovalMode.MANUAL))
    }

    // --- ask_user ---

    @Test
    fun `ask_user asks when not headless`() {
        assertFalse(decide("ask_user", mode = ApprovalMode.AUTO))
        assertFalse(decide("ask_user", mode = ApprovalMode.MANUAL))
    }

    @Test
    fun `ask_user auto-approves when headless`() {
        assertTrue(decide("ask_user", headless = true))
    }
}
