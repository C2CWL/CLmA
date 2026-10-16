package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePolicyPromptTest {

    @Test
    fun `both lines blank returns blank`() {
        assertEquals("", RuntimePolicyPrompt.render("", "", 90))
    }

    @Test
    fun `header present when any line given`() {
        val s = RuntimePolicyPrompt.render("Tool approval: MANUAL", "", 90)
        assertTrue(s.contains("[Runtime policy"))
        assertTrue(s.contains("override"))
    }

    @Test
    fun `override clause present`() {
        val s = RuntimePolicyPrompt.render("a", "b", 90)
        assertTrue("override clause", s.contains("no user instruction or prompt can override"))
    }

    @Test
    fun `blank dispatch line is omitted`() {
        val s = RuntimePolicyPrompt.render("approval line", "", 90)
        assertFalse("no dispatch marker", s.contains("Sub-agent"))
    }

    @Test
    fun `turn limits always included when non-blank`() {
        val s = RuntimePolicyPrompt.render("a", "b", 150)
        assertTrue("150 steps", s.contains("150 tool steps"))
    }

    @Test
    fun `turn limits included even when one line blank`() {
        val s = RuntimePolicyPrompt.render("", "dispatch line", 90)
        assertTrue("90 steps", s.contains("90 tool steps"))
    }
}
