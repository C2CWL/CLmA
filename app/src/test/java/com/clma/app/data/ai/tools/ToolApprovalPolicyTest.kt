package com.clma.app.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mode-era approval decision, exercised as a pure function so the whole matrix runs on
 * a bare JVM. Ordering matters and each branch gets its own case.
 *
 * ★拍板 (2026-10-16): Auto waives opaque relayed `mcp__*` tools — an agent build whose MCP
 * calls all ask is not autonomous. The named privilege-escalation floor
 * ([ToolApprovalDefaults.NO_ALWAYS_ALLOW]) is still checked before the mode, so Auto cannot
 * waive it — only the user's explicit, risk-acknowledged per-tool waiver (`floorWaived`) or an
 * in-memory "allow for this chat" grant on that tool can.
 */
class ToolApprovalPolicyTest {

    private fun decide(
        toolName: String,
        blacklist: Set<String> = emptySet(),
        mode: ApprovalMode = ApprovalMode.SEMI,
        headless: Boolean = false,
        allowedForChat: Boolean = false,
        floorWaived: Set<String> = emptySet(),
        mcpRequireApproval: Boolean = true,
    ): Boolean = ToolApprovalPolicy.shouldAutoApprove(
        toolName = toolName,
        blacklist = blacklist,
        mode = mode,
        headless = headless,
        allowedForChat = allowedForChat,
        floorWaived = floorWaived,
        mcpRequireApproval = mcpRequireApproval,
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
    fun `auto waives relayed mcp tools`() {
        assertTrue(decide("mcp__server__tool", mode = ApprovalMode.AUTO))
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
    fun `opaque mcp tools still ask in semi and manual`() {
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
    fun `a chat grant approves a non-waived floor tool in every mode`() {
        for (mode in ApprovalMode.entries) {
            assertTrue("$mode", decide("eval_javascript", allowedForChat = true, mode = mode))
            assertTrue("$mode", decide("keystore_decrypt", allowedForChat = true, mode = mode))
        }
    }

    @Test
    fun `a chat grant approves a relayed mcp tool in every mode`() {
        for (mode in ApprovalMode.entries) {
            assertTrue("$mode", decide("mcp__server__tool", allowedForChat = true, mode = mode))
        }
    }

    @Test
    fun `without a chat grant the floor and mcp tools still ask`() {
        assertFalse(decide("eval_javascript", allowedForChat = false, mode = ApprovalMode.SEMI))
        assertFalse(decide("eval_javascript", allowedForChat = false, mode = ApprovalMode.AUTO))
        assertFalse(decide("mcp__server__tool", allowedForChat = false, mode = ApprovalMode.SEMI))
        assertFalse(decide("mcp__server__tool", allowedForChat = false, mode = ApprovalMode.MANUAL))
    }

    @Test
    fun `a chat grant never turns ask_user into an auto-approval`() {
        assertFalse(decide("ask_user", allowedForChat = true, mode = ApprovalMode.AUTO))
        assertFalse(decide("request_secret", allowedForChat = true, mode = ApprovalMode.SEMI))
    }

    // --- user-acknowledged waivers of the default-ask floor ---

    @Test
    fun `a waived floor tool is approved in semi when not blacklisted`() {
        assertTrue(decide("browser_eval_js", mode = ApprovalMode.SEMI, floorWaived = setOf("browser_eval_js")))
    }

    @Test
    fun `a waived floor tool still asks in semi when blacklisted`() {
        assertFalse(
            decide(
                "browser_eval_js",
                blacklist = setOf("browser_eval_js"),
                mode = ApprovalMode.SEMI,
                floorWaived = setOf("browser_eval_js"),
            )
        )
    }

    @Test
    fun `a waived floor tool is approved in auto`() {
        assertTrue(decide("mcp_add", mode = ApprovalMode.AUTO, floorWaived = setOf("mcp_add")))
    }

    @Test
    fun `a waived floor tool still asks in manual`() {
        assertFalse(decide("eval_javascript", mode = ApprovalMode.MANUAL, floorWaived = setOf("eval_javascript")))
    }

    @Test
    fun `a waiver only covers the named floor tool`() {
        val waived = setOf("eval_javascript")
        assertTrue(decide("eval_javascript", mode = ApprovalMode.SEMI, floorWaived = waived))
        assertFalse(decide("browser_eval_js", mode = ApprovalMode.SEMI, floorWaived = waived))
        assertFalse(decide("keystore_decrypt", mode = ApprovalMode.AUTO, floorWaived = waived))
    }

    // --- MCP switch ---

    @Test
    fun `mcp switch off approves relayed mcp tools in semi`() {
        assertTrue(decide("mcp__server__tool", mode = ApprovalMode.SEMI, mcpRequireApproval = false))
    }

    @Test
    fun `mcp switch off does not affect manual`() {
        assertFalse(decide("mcp__server__tool", mode = ApprovalMode.MANUAL, mcpRequireApproval = false))
    }

    @Test
    fun `mcp switch on keeps auto waiving and semi asking`() {
        assertTrue(decide("mcp__server__tool", mode = ApprovalMode.AUTO, mcpRequireApproval = true))
        assertFalse(decide("mcp__server__tool", mode = ApprovalMode.SEMI, mcpRequireApproval = true))
    }

    @Test
    fun `mcp switch does not waive the named mcp management floor`() {
        assertFalse(decide("mcp_add", mode = ApprovalMode.SEMI, mcpRequireApproval = false))
        assertFalse(decide("mcp_update", mode = ApprovalMode.SEMI, mcpRequireApproval = false))
    }

    @Test
    fun `default parameters keep the pre-waiver behaviour`() {
        val noWaiver = { name: String, mode: ApprovalMode ->
            ToolApprovalPolicy.shouldAutoApprove(
                toolName = name,
                blacklist = emptySet(),
                mode = mode,
                headless = false,
                allowedForChat = false,
            )
        }
        for (mode in ApprovalMode.entries) {
            assertFalse("$mode", noWaiver("eval_javascript", mode))
        }
        assertFalse(noWaiver("mcp__s__t", ApprovalMode.SEMI))
        assertTrue(noWaiver("mcp__s__t", ApprovalMode.AUTO))
    }

    @Test
    fun `headless ignores waivers and switches (still approves, refusal lives elsewhere)`() {
        assertTrue(decide("eval_javascript", headless = true, mode = ApprovalMode.MANUAL, floorWaived = emptySet()))
    }

    @Test
    fun `auto still refuses the named floor even for mcp management tools`() {
        assertFalse(decide("mcp_add", mode = ApprovalMode.AUTO))
        assertFalse(decide("mcp_update", mode = ApprovalMode.AUTO))
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

    // --- ask_user / request_secret (human-input tools) ---

    @Test
    fun `ask_user asks when not headless`() {
        assertFalse(decide("ask_user", mode = ApprovalMode.AUTO))
        assertFalse(decide("ask_user", mode = ApprovalMode.MANUAL))
    }

    @Test
    fun `ask_user auto-approves when headless`() {
        assertTrue(decide("ask_user", headless = true))
    }

    @Test
    fun `request_secret asks when not headless`() {
        assertFalse(decide("request_secret", mode = ApprovalMode.AUTO))
        assertFalse(decide("request_secret", mode = ApprovalMode.MANUAL))
        assertFalse(decide("request_secret", mode = ApprovalMode.SEMI))
    }

    @Test
    fun `request_secret auto-approves when headless`() {
        // Headless then hits the tool body which returns request_secret_unavailable.
        assertTrue(decide("request_secret", headless = true))
    }
}
