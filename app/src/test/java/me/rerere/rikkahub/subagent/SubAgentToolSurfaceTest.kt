package me.rerere.rikkahub.subagent

import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-09 / (8) — the sub-agent tool-surface POLICY (the headless floor).
 *
 * The load-bearing property asserted below: the policy removes exactly the tools a headless
 * sub-agent must not receive (the `subagent_` handles, the per-call-approval tools, `ask_user`,
 * the device-UI-bound tools, the privacy-sensitive capture tools) and nothing else.
 *
 * The per-conversation freeze registry that applies this policy (and, since P2-04, the child's own
 * surface assistant) is covered by [SubAgentSurfaceTest].
 */
class SubAgentToolSurfaceTest {

    // ---- policy ---------------------------------------------------------------------

    @Test
    fun `the subagent handles are denied by prefix`() {
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_dispatch"))
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_list"))
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_get"))
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_cancel"))
        // Prefix rule, not a fixed list — a future handle is covered without editing the file.
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_something_new"))
        // P2-06b — the roster WRITE handles. A sub-agent run is itself headless, so it may not
        // rewrite the roster it was dispatched from.
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_create"))
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_update"))
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_delete"))
    }

    @Test
    fun `every NO_ALWAYS_ALLOW tool is denied`() {
        assertTrue(ToolApprovalDefaults.NO_ALWAYS_ALLOW.isNotEmpty())
        ToolApprovalDefaults.NO_ALWAYS_ALLOW.forEach { name ->
            assertTrue(name, SubAgentToolSurface.isDenied(name))
            // Two of them (nfc_write_tag / grant_directory_access) are also device-UI-bound, and
            // the UI reason wins because "it cannot run headless at all" is the more useful thing
            // to tell a dispatcher. Everything else reports the approval reason.
            val expected = if (name in SubAgentToolSurface.UI_BOUND_TOOL_NAMES) {
                "tool_unavailable_headless"
            } else {
                "tool_not_authorized"
            }
            assertEquals(name, expected, SubAgentToolSurface.denialReason(name))
        }
    }

    @Test
    fun `ask_user is denied so a headless run does not burn a trip on it`() {
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("ask_user"))
        assertFalse(SubAgentToolSurface.safeNames(listOf("ask_user")).contains("ask_user"))
    }

    @Test
    fun `device UI bound tools are denied`() {
        val expected = setOf(
            "take_photo",
            "verify_fingerprint",
            "nfc_read_tag",
            "nfc_write_tag",
            "grant_directory_access",
        )
        assertEquals(expected, SubAgentToolSurface.UI_BOUND_TOOL_NAMES)
        expected.forEach { name ->
            assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason(name))
        }
    }

    @Test
    fun `privacy sensitive capture tools are denied`() {
        assertEquals(
            setOf("record_audio", "speech_to_text"),
            SubAgentToolSurface.PRIVACY_SENSITIVE_TOOL_NAMES,
        )
        // Ground truth: both are in the recommended set (they read/ship the user's surroundings),
        // and a headless run is exactly the world that would skip the consent they deserve.
        SubAgentToolSurface.PRIVACY_SENSITIVE_TOOL_NAMES.forEach { name ->
            assertTrue(name, name in ToolApprovalDefaults.DANGEROUS_TOOLS)
            assertEquals(name, "tool_not_authorized", SubAgentToolSurface.denialReason(name))
            assertFalse(name, SubAgentToolSurface.safeNames(listOf(name)).contains(name))
        }
    }

    @Test
    fun `ordinary tools survive the policy`() {
        val kept = listOf(
            "search_web",
            "web_fetch",
            "workspace_shell",
            "use_skill",
            "memory_tool",
            "mcp__github__get_me",
            "browser",
            "termux_run_command",
            "open_file",
            "show_toast",
        )
        assertEquals(kept.toSet(), SubAgentToolSurface.safeNames(kept))
        kept.forEach { assertNull(it, SubAgentToolSurface.denialReason(it)) }
    }

    @Test
    fun `safeNames is a subtraction and never invents names`() {
        val mixed = listOf("search_web", "subagent_dispatch", "ask_user", "eval_javascript", "take_photo")
        assertEquals(setOf("search_web"), SubAgentToolSurface.safeNames(mixed))
        assertTrue(SubAgentToolSurface.safeNames(emptyList()).isEmpty())
    }
}
