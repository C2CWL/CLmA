package com.clma.app.data.ai

import com.clma.ai.core.MessageRole
import com.clma.ai.core.TokenUsage
import com.clma.ai.provider.Model
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PreSendContextGuardTest {
    private val earlier = LocalDateTime(2026, 10, 22, 10, 0)
    private val compactedAt = LocalDateTime(2026, 10, 22, 11, 0)
    private val later = LocalDateTime(2026, 10, 22, 12, 0)

    private fun reply(totalTokens: Int, createdAt: LocalDateTime = earlier) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text("x".repeat(300))),
        createdAt = createdAt,
        usage = TokenUsage(promptTokens = totalTokens - 100, completionTokens = 100, totalTokens = totalTokens),
    )

    // 300 ASCII chars = 100 tokens + 8 message overhead.
    private fun user() = UIMessage.user("x".repeat(300))

    @Test
    fun `new user turn at the trigger is compacted before sending`() {
        // Last reported usage 10_000 + new user message 108 = 10_108.
        val decision = PreSendContextGuard.decide(
            messages = listOf(user(), reply(10_000), user()),
            autoCompactionEnabled = true,
            triggerTokens = 10_108,
            hardLimitTokens = 128_000,
        )
        assertEquals(PreSendContextDecision.Compact(10_108, 10_108), decision)
    }

    @Test
    fun `new user turn below the trigger is sent`() {
        val decision = PreSendContextGuard.decide(
            messages = listOf(user(), reply(10_000), user()),
            autoCompactionEnabled = true,
            triggerTokens = 10_109,
            hardLimitTokens = 128_000,
        )
        assertEquals(PreSendContextDecision.Send, decision)
    }

    @Test
    fun `unknown threshold in percent mode never compacts`() {
        val decision = PreSendContextGuard.decide(
            messages = listOf(user(), reply(900_000), user()),
            autoCompactionEnabled = true,
            triggerTokens = null,
            hardLimitTokens = null,
        )
        assertEquals(PreSendContextDecision.Send, decision)
    }

    @Test
    fun `tool continuation inside a turn keeps the usage driven path`() {
        // The tail is the assistant's tool round, not a new user message.
        val decision = PreSendContextGuard.decide(
            messages = listOf(user(), reply(50_000)),
            autoCompactionEnabled = true,
            triggerTokens = 1_000,
            hardLimitTokens = 8_000,
        )
        assertEquals(PreSendContextDecision.Send, decision)
        val off = PreSendContextGuard.decide(
            messages = listOf(user(), reply(50_000)),
            autoCompactionEnabled = false,
            triggerTokens = null,
            hardLimitTokens = 8_000,
        )
        assertEquals(PreSendContextDecision.Send, off)
    }

    @Test
    fun `auto compaction off refuses a turn clearly over the model window`() {
        // 1_049_000 reported + 108 > 1_000_000 * 110%? No: 1_049_108 < 1_100_000 -> sent.
        assertEquals(
            PreSendContextDecision.Send,
            PreSendContextGuard.decide(
                messages = listOf(user(), reply(1_049_000), user()),
                autoCompactionEnabled = false,
                triggerTokens = null,
                hardLimitTokens = 1_000_000,
            ),
        )
        // 1_200_000 + 108 is well over the tolerance -> refused, no request goes out.
        assertEquals(
            PreSendContextDecision.OverHardLimit(1_200_108, 1_000_000),
            PreSendContextGuard.decide(
                messages = listOf(user(), reply(1_200_000), user()),
                autoCompactionEnabled = false,
                triggerTokens = null,
                hardLimitTokens = 1_000_000,
            ),
        )
    }

    @Test
    fun `auto compaction off with unknown window always sends`() {
        val decision = PreSendContextGuard.decide(
            messages = listOf(user(), reply(5_000_000), user()),
            autoCompactionEnabled = false,
            triggerTokens = null,
            hardLimitTokens = null,
        )
        assertEquals(PreSendContextDecision.Send, decision)
    }

    @Test
    fun `usage reported before the compaction is ignored`() {
        // The reply's usage describes the old, uncompacted request; the compacted view is small.
        val messages = listOf(user(), reply(200_000, createdAt = earlier), user())
        val decision = PreSendContextGuard.decide(
            messages = messages,
            autoCompactionEnabled = true,
            triggerTokens = 100_000,
            hardLimitTokens = 128_000,
            compactedAt = compactedAt,
        )
        assertEquals(PreSendContextDecision.Send, decision)
        assertEquals(324, PreSendContextGuard.estimateTokens(messages, compactedAt))
    }

    @Test
    fun `usage reported after the compaction is trusted`() {
        val messages = listOf(user(), reply(120_000, createdAt = later), user())
        val decision = PreSendContextGuard.decide(
            messages = messages,
            autoCompactionEnabled = true,
            triggerTokens = 100_000,
            hardLimitTokens = 128_000,
            compactedAt = compactedAt,
        )
        assertEquals(PreSendContextDecision.Compact(120_108, 100_000), decision)
    }

    @Test
    fun `hard limit comes from provider metadata or the registry, not the threshold`() {
        assertEquals(64_000, PreSendContextGuard.hardContextLimit(Model(modelId = "deepseek-chat", contextLength = 64_000)))
        assertEquals(128_000, PreSendContextGuard.hardContextLimit(Model(modelId = "deepseek-chat")))
        assertEquals(1_000_000, PreSendContextGuard.hardContextLimit(Model(modelId = "deepseek-v4-pro")))
        assertEquals(null, PreSendContextGuard.hardContextLimit(Model(modelId = "some-unknown-model")))
    }
}
