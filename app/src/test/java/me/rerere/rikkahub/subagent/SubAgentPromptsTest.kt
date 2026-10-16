package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three-layer system-prompt chain (assistant default → expert → per-dispatch) that
 * the engine assembles for every sub-agent run, plus the two behaviors that used to be bugs:
 * the assistant's blank prompt falling back to [SubAgentDefaults.DEFAULT_SYSTEM_PROMPT] (the
 * constant was dead code), and the dispatch call's own `system_prompt` actually landing (the
 * parameter was parsed and dropped).
 */
class SubAgentPromptsTest {

    @Test
    fun `blank assistant prompt falls back to the default`() {
        val out = composeSubAgentSystemPrompt(
            assistantPrompt = "  ",
            expertPrompt = null,
            requestPrompt = null,
        )
        assertEquals(SubAgentDefaults.DEFAULT_SYSTEM_PROMPT.trim(), out)
    }

    @Test
    fun `assistant prompt replaces the default when set`() {
        val out = composeSubAgentSystemPrompt(
            assistantPrompt = "Assistant rules.",
            expertPrompt = null,
            requestPrompt = null,
        )
        assertEquals("Assistant rules.", out)
        assertTrue(!out.contains(SubAgentDefaults.DEFAULT_SYSTEM_PROMPT.trim()))
    }

    @Test
    fun `all three layers stack in precedence order`() {
        val out = composeSubAgentSystemPrompt(
            assistantPrompt = "AAA",
            expertPrompt = "EEE",
            requestPrompt = "RRR",
        )
        assertEquals("AAA\n\nEEE\n\nRRR", out)
    }

    @Test
    fun `blank expert and request layers are dropped`() {
        val out = composeSubAgentSystemPrompt(
            assistantPrompt = "AAA",
            expertPrompt = "   ",
            requestPrompt = "",
        )
        assertEquals("AAA", out)
    }

    @Test
    fun `layers are trimmed`() {
        val out = composeSubAgentSystemPrompt(
            assistantPrompt = "  AAA  ",
            expertPrompt = "\nEEE\n",
            requestPrompt = " RRR ",
        )
        assertEquals("AAA\n\nEEE\n\nRRR", out)
    }

    @Test
    fun `the chain is never empty`() {
        // Even with every layer blank the default keeps the run under scoping discipline —
        // this is the invariant the engine relies on when it registers the addendum.
        assertTrue(
            composeSubAgentSystemPrompt(
                assistantPrompt = "",
                expertPrompt = null,
                requestPrompt = null,
            ).isNotBlank()
        )
    }
}
