package com.clma.app.data.preferences

import com.clma.app.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for [sanitizeFloorWaivers]: the persisted `floor_waived` set may only ever hold
 * names from the default-ask floor ([ToolApprovalDefaults.NO_ALWAYS_ALLOW]), so a stale or
 * hand-edited entry can never widen what the user waived.
 */
class ToolApprovalFloorWaiverTest {

    @Test
    fun keepsFloorNames() {
        val stored = setOf("eval_javascript", "browser_eval_js", "keystore_decrypt")
        assertEquals(stored, sanitizeFloorWaivers(stored))
    }

    @Test
    fun dropsNamesOutsideTheFloor() {
        val stored = setOf("eval_javascript", "termux_run_command", "read_file", "mcp__srv__tool", "")
        assertEquals(setOf("eval_javascript"), sanitizeFloorWaivers(stored))
    }

    @Test
    fun emptyStaysEmpty() {
        assertTrue(sanitizeFloorWaivers(emptySet()).isEmpty())
    }

    @Test
    fun everyFloorToolCanBeWaived() {
        assertEquals(ToolApprovalDefaults.NO_ALWAYS_ALLOW, sanitizeFloorWaivers(ToolApprovalDefaults.NO_ALWAYS_ALLOW))
    }
}
