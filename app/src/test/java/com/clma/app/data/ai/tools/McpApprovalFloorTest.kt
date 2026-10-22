package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCP install tools are a load-bearing security surface: a hostile MCP server can exfiltrate
 * everything reachable through the assistant, so `mcp_add` / `mcp_update` sit on the
 * **non-waivable hard floor** — every call is confirmed and no blacklist entry, chat grant or
 * other shortcut can quietly waive them. `eval_javascript` rides the same floor for the same
 * reason (attacker-controllable code).
 *
 * The other MCP control writes are ordinary side-effecting tools now: they are offered in the
 * recommended blacklist set, so the user can make them ask, but nothing forces it.
 */
class McpApprovalFloorTest {

    @Test
    fun `mcp_add and mcp_update are on the hard floor`() {
        assertTrue(ToolApprovalDefaults.isHardFloor("mcp_add"))
        assertTrue(ToolApprovalDefaults.isHardFloor("mcp_update"))
    }

    @Test
    fun `eval_javascript is on the hard floor`() {
        assertTrue(ToolApprovalDefaults.isHardFloor("eval_javascript"))
        assertTrue("eval_javascript" in ToolApprovalDefaults.DANGEROUS_TOOLS)
    }

    @Test
    fun `other mcp control writes are not on the floor but are recommended`() {
        for (name in listOf("mcp_delete", "mcp_set_enabled", "mcp_set_tool_approval")) {
            assertFalse(name, ToolApprovalDefaults.isHardFloor(name))
            assertTrue("$name should be offered in the recommended set", name in ToolApprovalDefaults.DANGEROUS_TOOLS)
        }
    }

    @Test
    fun `read-only mcp control tools are not on the floor`() {
        for (name in listOf("mcp_list", "mcp_get", "mcp_test", "mcp_list_tools")) {
            assertFalse(name, ToolApprovalDefaults.isHardFloor(name))
        }
    }

    @Test
    fun `any relayed mcp tool is on the floor`() {
        assertTrue(ToolApprovalDefaults.isHardFloor("mcp__server__anything"))
    }
}
