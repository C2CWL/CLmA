package com.clma.app.data.ai

import com.clma.ai.core.MessageRole
import com.clma.ai.core.TokenUsage
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetPlannerTest {
    @Test
    fun `reported usage is used as baseline for trailing messages`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("prior response")),
                usage = TokenUsage(
                    promptTokens = 5_000,
                    completionTokens = 1_000,
                    totalTokens = 6_000,
                ),
            ),
            UIMessage.user("x".repeat(300)),
        )

        assertEquals(6_108, ContextBudgetPlanner.estimateInputTokens(messages))
    }

    @Test
    fun `tool output appended after provider usage is included in the next request estimate`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "read_file",
                        input = "{\"path\":\"notes.txt\"}",
                        output = listOf(UIMessagePart.Text("x".repeat(3_000))),
                    ),
                ),
                usage = TokenUsage(
                    promptTokens = 5_000,
                    completionTokens = 1_000,
                    totalTokens = 6_000,
                ),
            ),
        )

        assertEquals(7_000, ContextBudgetPlanner.estimateInputTokens(messages))
    }

    @Test
    fun `context estimate ignores pre compaction provider usage`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("x".repeat(300))),
                usage = TokenUsage(promptTokens = 10_000, completionTokens = 100, totalTokens = 10_100),
            ),
        )

        assertEquals(108, ContextBudgetPlanner.estimateContextTokens(messages))
    }

    @Test
    fun `context estimate conservatively counts CJK tool output`() {
        val messages = listOf(UIMessage.user("文".repeat(300)))

        assertEquals(308, ContextBudgetPlanner.estimateContextTokens(messages))
    }

    @Test
    fun `plan triggers at configured percentage`() {
        val below = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
                usage = TokenUsage(
                    promptTokens = 5_000,
                    completionTokens = 1_000,
                    totalTokens = 6_000,
                ),
            ),
            UIMessage.user("x".repeat(300)),
        )
        val above = below.dropLast(1) + UIMessage.user("x".repeat(1_200))

        assertFalse(ContextBudgetPlanner.plan(below, 8_000, 80).shouldCompact)
        assertTrue(ContextBudgetPlanner.plan(above, 8_000, 80).shouldCompact)
        assertEquals(6_400, ContextBudgetPlanner.plan(above, 8_000, 80).triggerTokens)
        assertEquals(400, ContextBudgetPlanner.plan(above, 8_000, 5).triggerTokens)
    }

    @Test
    fun `plan supports a manual token threshold in thousands`() {
        val messages = listOf(UIMessage.user("x".repeat(36_000)))

        val plan = ContextBudgetPlanner.plan(
            messages = messages,
            contextLength = 128_000,
            thresholdPercent = 80,
            thresholdTokensK = 12,
        )

        assertEquals(12_000, plan.triggerTokens)
        assertTrue(plan.shouldCompact)
    }

    @Test
    fun `plan reserves output and prompt budget before triggering`() {
        val plan = ContextBudgetPlanner.plan(
            messages = listOf(UIMessage.user("x".repeat(24_000))),
            contextLength = 10_000,
            thresholdPercent = 95,
            reservedTokens = 3_000,
        )

        assertEquals(7_000, plan.triggerTokens)
        assertTrue(plan.shouldCompact)
    }

    @Test
    fun `tail selection keeps minimum recent messages`() {
        val messages = List(6) { UIMessage.user("x".repeat(300)) }

        assertEquals(
            2,
            ContextBudgetPlanner.chooseTailStartIndex(
                messages = messages,
                targetTokens = 250,
                minimumRecentMessages = 4,
            ),
        )
    }

    @Test
    fun `document is estimated from the text that was actually inlined`() {
        DocumentTokenEstimates.clear()
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Document(url = "file:///upload/a.docx", fileName = "a.docx")),
        )
        // Unknown document: fixed guess 1_024 + "a.docx" (2) + overhead 8.
        assertEquals(1_034, ContextBudgetPlanner.estimateContextTokens(listOf(message)))

        DocumentTokenEstimates.record("file:///upload/a.docx", 500_000)
        assertEquals(500_010, ContextBudgetPlanner.estimateContextTokens(listOf(message)))
        DocumentTokenEstimates.clear()
    }

    @Test
    fun `inlined document text is not counted twice`() {
        DocumentTokenEstimates.record("file:///upload/a.txt", 500_000)
        val inlined = "<UploadFile name=\"a.txt\">\n```\n" + "文".repeat(100) + "\n```\n</UploadFile>"
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(inlined),
                UIMessagePart.Document(url = "file:///upload/a.txt", fileName = "a.txt"),
            ),
        )
        val expected = 8L + ContextBudgetPlanner.estimateTextTokens(inlined)
        assertEquals(expected, ContextBudgetPlanner.estimateMessageTokens(message))
        DocumentTokenEstimates.clear()
    }
}
