package me.rerere.rikkahub.data.usage

import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageRecordMappingTest {

    private val usage = TokenUsage(
        promptTokens = 1000,
        completionTokens = 50,
        cachedTokens = 800,
        totalTokens = 1050,
        cost = 0.012,
        cachedTokensReported = true,
        cacheMissTokens = 200,
        cacheWriteTokens = 12,
        reasoningTokens = 30,
    )

    private fun map(
        usage: TokenUsage = this.usage,
        purpose: UsagePurpose = UsagePurpose.MAIN,
    ) = UsageRecordMapper.from(
        id = "row-1",
        purpose = purpose,
        usage = usage,
        createdAtMs = 1_700_000_000_000,
        providerId = "deepseek",
        modelId = "deepseek-chat",
        assistantId = "asst-1",
        conversationId = "conv-1",
        parentRunId = "run-root",
        streaming = true,
        latencyMs = 1234,
    )

    @Test
    fun `every token column is copied, including the nullable provenance fields`() {
        val row = map()
        assertEquals(1000, row.inputTokens)
        assertEquals(50, row.outputTokens)
        assertEquals(1050, row.totalTokens)
        assertEquals(800, row.cachedTokens)
        assertEquals(true, row.cachedTokensReported)
        assertEquals(200, row.cacheMissTokens)
        assertEquals(12, row.cacheWriteTokens)
        assertEquals(30, row.reasoningTokens)
        assertEquals(0.012, row.providerCostUsd!!, 1e-9)
        assertEquals("deepseek", row.providerId)
        assertEquals("deepseek-chat", row.modelId)
        assertEquals("asst-1", row.assistantId)
        assertEquals("conv-1", row.conversationId)
        assertEquals("run-root", row.parentRunId)
        assertEquals(true, row.streaming)
        assertEquals(1234L, row.latencyMs)
    }

    @Test
    fun `an unreported cache stays unreported in the row`() {
        val row = map(usage = TokenUsage(promptTokens = 10, completionTokens = 2))
        assertEquals(0, row.cachedTokens)
        assertEquals(false, row.cachedTokensReported)
        assertNull(row.cacheMissTokens)
        assertNull(row.cacheWriteTokens)
        assertNull(row.reasoningTokens)
        assertNull(row.costMicros)
        assertNull(row.priceVersionId)
    }

    @Test
    fun `the purpose is stored as a stable enum name`() {
        val row = map(purpose = UsagePurpose.COMPACTION)
        assertEquals("COMPACTION", row.purpose)
        assertEquals(UsagePurpose.COMPACTION, UsagePurpose.valueOf(row.purpose))
    }

    @Test
    fun `retention cutoff is exactly ninety days back`() {
        val now = 1_700_000_000_000L
        assertEquals(now - 90L * 24 * 60 * 60 * 1000, UsageRecordMapper.retentionCutoffMs(now))
        assertEquals(now - 7L * 24 * 60 * 60 * 1000, UsageRecordMapper.retentionCutoffMs(now, 7))
    }
}
