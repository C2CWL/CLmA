package com.clma.app.data.usage

import kotlinx.coroutines.runBlocking
import com.clma.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class LifetimeUsageTest {

    @Test
    fun `legacy floor keeps the larger known total in each category`() {
        val merged = LifetimeUsage.floor(
            ledger = LifetimeUsage(inputTokens = 500, outputTokens = 20, cachedTokens = 0),
            legacy = LifetimeUsage(inputTokens = 120, outputTokens = 80, cachedTokens = 30),
        )

        assertEquals(500L, merged.inputTokens)
        assertEquals(80L, merged.outputTokens)
        assertEquals(30L, merged.cachedTokens)
        assertEquals(true, merged.legacyBaselined)
    }

    @Test
    fun `recording raises the durable total and pruning the detail does not lower it`() = runBlocking {
        val dao = RecordingDao()
        val ledger = UsageLedger(
            dao = dao,
            nowMs = { 1_700_000_000_000 },
            newId = { "row-${dao.rows.size}" },
            legacyUsage = { LifetimeUsage(inputTokens = 40, outputTokens = 5, cachedTokens = 1) },
        )

        ledger.record(UsagePurpose.MAIN, usage(prompt = 10, completion = 4, cached = 2))
        ledger.record(UsagePurpose.MAIN, usage(prompt = 3, completion = 1, cached = 0))
        val removed = ledger.prune(at = Long.MAX_VALUE)

        assertEquals(2, removed)
        assertEquals(0, dao.rows.size)
        val total = ledger.lifetimeUsage()
        assertEquals(53L, total.inputTokens)
        assertEquals(10L, total.outputTokens)
        assertEquals(3L, total.cachedTokens)
    }

    @Test
    fun `an already baselined restore is not raised by the current message history`() = runBlocking {
        val dao = RecordingDao(
            LifetimeUsage(inputTokens = 900, outputTokens = 100, cachedTokens = 10, legacyBaselined = true)
        )
        val ledger = UsageLedger(
            dao = dao,
            legacyUsage = { LifetimeUsage(inputTokens = 1, outputTokens = 1, cachedTokens = 1) },
        )

        val total = ledger.lifetimeUsage()
        assertEquals(900L, total.inputTokens)
        assertEquals(100L, total.outputTokens)
        assertEquals(10L, total.cachedTokens)
    }

    private fun usage(prompt: Int, completion: Int, cached: Int) = TokenUsage(
        promptTokens = prompt,
        completionTokens = completion,
        cachedTokens = cached,
        totalTokens = prompt + completion,
        cachedTokensReported = true,
    )

    private class RecordingDao(
        initial: LifetimeUsage = LifetimeUsage(),
    ) : UsageRecordDao {
        val rows = mutableListOf<UsageRecordEntity>()
        private var lifetime = initial

        override suspend fun insert(record: UsageRecordEntity) {
            rows += record
        }

        override suspend fun ensureLifetimeRow(usage: LifetimeUsage) {
            if (lifetime == LifetimeUsage()) lifetime = usage
        }

        override suspend fun lifetimeUsage(): LifetimeUsage = lifetime

        override suspend fun addLifetimeTokens(input: Int, output: Int, cached: Int) {
            lifetime = lifetime.copy(
                inputTokens = lifetime.inputTokens + input.coerceAtLeast(0),
                outputTokens = lifetime.outputTokens + output.coerceAtLeast(0),
                cachedTokens = lifetime.cachedTokens + cached.coerceAtLeast(0),
            )
        }

        override suspend fun applyLegacyFloor(input: Long, output: Long, cached: Long) {
            if (lifetime.legacyBaselined) return
            lifetime = LifetimeUsage.floor(
                lifetime,
                LifetimeUsage(inputTokens = input, outputTokens = output, cachedTokens = cached),
            )
        }

        override suspend fun latest(limit: Int) = rows.takeLast(limit).reversed()
        override suspend fun since(sinceMs: Long, limit: Int) = rows.filter { it.createdAtMs >= sinceMs }
        override suspend fun count() = rows.size
        override suspend fun tokensForParentRun(parentRunId: String) = 0L
        override suspend fun tokensSince(sinceMs: Long) = 0L
        override suspend fun forConversation(conversationId: String, limit: Int) = emptyList<UsageRecordEntity>()
        override suspend fun botUsageSince(botId: String, sinceMs: Long) =
            com.clma.app.data.botmode.BotUsageTotal(0, 0.0)

        override suspend fun deleteOlderThan(cutoffMs: Long): Int {
            val before = rows.size
            rows.removeAll { it.createdAtMs < cutoffMs }
            return before - rows.size
        }
    }
}
