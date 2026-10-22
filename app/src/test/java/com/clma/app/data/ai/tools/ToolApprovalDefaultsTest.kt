package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The static knowledge behind the blacklist-era approval decision: the non-waivable hard floor,
 * the opaque `mcp__*` tools that ride on it, and the curated "recommended blacklist" set the
 * Settings screen offers.
 */
class ToolApprovalDefaultsTest {

    @Test
    fun `privilege-escalation tools are on the hard floor`() {
        assertTrue(ToolApprovalDefaults.isHardFloor("eval_javascript"))
        assertTrue(ToolApprovalDefaults.isHardFloor("keystore_decrypt"))
        assertTrue(ToolApprovalDefaults.isHardFloor("grant_directory_access"))
        assertTrue(ToolApprovalDefaults.isHardFloor("mcp_add"))
        assertTrue(ToolApprovalDefaults.isHardFloor("nfc_write_tag"))
    }

    @Test
    fun `opaque mcp relayed tools are on the hard floor`() {
        assertTrue(ToolApprovalDefaults.isHardFloor("mcp__server__do_something"))
        assertTrue(ToolApprovalDefaults.isHardFloor("mcp__"))
    }

    @Test
    fun `ordinary tools are not on the floor`() {
        // launch_activity (issue #42) is a normal side-effecting tool now: it asks only when
        // the user blacklists it, because the built-in "always ask" set is gone.
        assertFalse(ToolApprovalDefaults.isHardFloor("launch_activity"))
        assertFalse(ToolApprovalDefaults.isHardFloor("read_file"))
        assertFalse(ToolApprovalDefaults.isHardFloor(""))
    }

    @Test
    fun `the recommended set still lists the classic side-effecting tools`() {
        assertTrue("launch_activity" in ToolApprovalDefaults.DANGEROUS_TOOLS)
        assertTrue("termux_run_command" in ToolApprovalDefaults.DANGEROUS_TOOLS)
        assertTrue("write_text_file" in ToolApprovalDefaults.DANGEROUS_TOOLS)
    }
}
