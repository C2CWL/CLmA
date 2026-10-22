package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.ContextCompactionPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultTruncationTest {

    private fun ascii(chars: Int) = "x".repeat(chars)

    @Test
    fun `null budget leaves the text untouched`() {
        val text = ascii(10_000)

        val outcome = truncateToolResult(text, maxTokens = null)

        assertFalse(outcome.truncated)
        assertEquals(text, outcome.text)
        assertEquals(ContextCompactionPlanner.estimateTokens(text), outcome.originalTokens)
    }

    @Test
    fun `non positive budget is treated as off`() {
        val text = ascii(10_000)

        for (budget in listOf(0, -1, -100)) {
            val outcome = truncateToolResult(text, maxTokens = budget)
            assertFalse("budget=$budget should be a no-op", outcome.truncated)
            assertEquals(text, outcome.text)
        }
    }

    @Test
    fun `text within budget is returned verbatim`() {
        val text = ascii(300) // ~100 tokens

        val outcome = truncateToolResult(text, maxTokens = 200)

        assertFalse(outcome.truncated)
        assertEquals(text, outcome.text)
        assertEquals(0, outcome.elidedChars)
    }

    @Test
    fun `over budget keeps the head and the tail and elides the middle`() {
        val head = "HEAD-" + ascii(4_000)
        val middle = "MIDDLE-" + ascii(40_000)
        val tail = ascii(4_000) + "-TAIL"
        val text = head + middle + tail

        val outcome = truncateToolResult(text, maxTokens = 1_000, headRatio = 0.6)

        assertTrue(outcome.truncated)
        assertTrue("head must survive", outcome.text.contains("HEAD-"))
        assertTrue("tail must survive", outcome.text.contains("-TAIL"))
        assertFalse("middle must be dropped", outcome.text.contains("MIDDLE-"))
        assertTrue("marker must be present", outcome.text.contains("characters elided"))
        assertTrue("result must be shorter than the original", outcome.text.length < text.length)
    }

    @Test
    fun `result stays within the requested budget`() {
        val text = ascii(60_000)

        for (budget in listOf(50, 200, 1_000, 5_000)) {
            val outcome = truncateToolResult(text, maxTokens = budget)
            assertTrue("must truncate at budget=$budget", outcome.truncated)
            assertTrue(
                "budget=$budget produced ${outcome.resultTokens} tokens",
                outcome.resultTokens <= budget,
            )
        }
    }

    @Test
    fun `non ascii text respects the budget too`() {
        // Every CJK character costs a full token, so this is ~20k tokens of 20k characters.
        val text = "这是一个很长的工具输出结果，需要被截断以保护上下文预算。".repeat(500)

        val outcome = truncateToolResult(text, maxTokens = 500)

        assertTrue(outcome.truncated)
        assertTrue("budget overrun: ${outcome.resultTokens}", outcome.resultTokens <= 500)
        assertTrue("head kept", outcome.text.startsWith("这是一个很长的"))
        assertTrue("tail kept", outcome.text.trimEnd().endsWith("。"))
    }

    @Test
    fun `kept and elided character counts add up to the original length`() {
        val text = ascii(25_000)

        val outcome = truncateToolResult(text, maxTokens = 800)

        assertEquals(text.length, outcome.headChars + outcome.tailChars + outcome.elidedChars)
    }

    @Test
    fun `head only and tail only ratios are honoured`() {
        val text = "START-" + ascii(20_000) + "-END"
        val tailOnly = truncateToolResult(text, maxTokens = 500, headRatio = 0.0)
        val headOnly = truncateToolResult(text, maxTokens = 500, headRatio = 1.0)

        assertTrue(tailOnly.truncated)
        assertFalse("with headRatio 0 nothing may be kept from the head", tailOnly.text.contains("START-"))
        assertTrue("-END must survive when only the tail is kept", tailOnly.text.contains("-END"))

        assertTrue(headOnly.truncated)
        assertTrue("START- must survive when only the head is kept", headOnly.text.contains("START-"))
        assertFalse("-END must be dropped when only the head is kept", headOnly.text.contains("-END"))
        assertEquals(0, headOnly.tailChars)
    }

    @Test
    fun `a tiny budget still returns something usable instead of crashing`() {
        val text = ascii(10_000)

        for (budget in listOf(1, 2, 5, 10)) {
            val outcome = truncateToolResult(text, maxTokens = budget)
            assertTrue(outcome.truncated)
            assertTrue(outcome.text.isNotEmpty())
            // Below ~32 tokens the elision marker alone is bigger than the budget; the marker is
            // metadata and is deliberately allowed to overshoot rather than silently vanish.
            assertTrue(
                "budget=$budget produced ${outcome.resultTokens} tokens",
                outcome.resultTokens <= budget + 40,
            )
        }
    }

    @Test
    fun `head and tail never overlap`() {
        // Pathological: a huge budget relative to the text but still over it after the reserve.
        val text = ascii(40)
        val outcome = truncateToolResult(text, maxTokens = 1)

        assertTrue(outcome.truncated)
        assertEquals(text.length, outcome.headChars + outcome.tailChars + outcome.elidedChars)
        assertTrue(
            "text must not be duplicated: ${outcome.text.length} chars",
            outcome.text.length <= text.length + elisionMarker(0).length + 8,
        )
    }

    @Test
    fun `empty text is never reported as truncated`() {
        val outcome = truncateToolResult("", maxTokens = 100)

        assertFalse(outcome.truncated)
        assertEquals("", outcome.text)
    }
}
