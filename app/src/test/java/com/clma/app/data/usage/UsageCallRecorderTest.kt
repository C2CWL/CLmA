package me.rerere.rikkahub.data.usage

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCallRecorderTest {

    private class FakeDao : UsageRecordDao {
        val rows = mutableListOf<UsageRecordEntity>()
        var failWith: Throwable? = null

        override suspend fun insert(record: UsageRecordEntity) {
            failWith?.let { throw it }
            rows += record
        }

        override suspend fun latest(limit: Int) = rows.takeLast(limit).reversed()
        override suspend fun since(sinceMs: Long, limit: Int) = rows.filter { it.createdAtMs >= sinceMs }
        override suspend fun count() = rows.size
        override suspend fun forConversation(conversationId: String, limit: Int) =
            rows.filter { it.conversationId == conversationId }
                .sortedBy { it.createdAtMs }
                .take(limit)
        override suspend fun tokensForParentRun(parentRunId: String) = 0L
        override suspend fun tokensSince(sinceMs: Long) = 0L
        override suspend fun deleteOlderThan(cutoffMs: Long) = 0
    }

    private fun ledgerFor(dao: FakeDao) =
        UsageLedger(dao = dao, nowMs = { 1_700_000_000_000 }, newId = { "row-${dao.rows.size}" })

    private val reportedUsage = TokenUsage(
        promptTokens = 100,
        completionTokens = 7,
        cachedTokens = 64,
        totalTokens = 107,
        cachedTokensReported = true,
    )

    @Test
    fun `writes exactly one row carrying the ambient purpose and the provider provenance`() = runBlocking {
        val dao = FakeDao()
        val outcome = UsageCallRecorder.record(
            ledger = ledgerFor(dao),
            usage = reportedUsage,
            context = UsageCallContext(
                purpose = UsagePurpose.TOOL_LOOP,
                conversationId = "conv-1",
                assistantId = "asst-1",
            ),
            providerName = "DeepSeek",
            modelId = "deepseek-chat",
            latencyMs = 42,
        )

        assertTrue(outcome is UsageCallRecorder.Outcome.Recorded)
        assertEquals(1, dao.rows.size)
        val row = dao.rows.single()
        assertEquals("TOOL_LOOP", row.purpose)
        assertEquals("DeepSeek", row.providerId)
        assertEquals("deepseek-chat", row.modelId)
        assertEquals("conv-1", row.conversationId)
        assertEquals("asst-1", row.assistantId)
        assertEquals(100, row.inputTokens)
        assertEquals(7, row.outputTokens)
        assertEquals(64, row.cachedTokens)
        assertEquals(true, row.cachedTokensReported)
        assertEquals(42L, row.latencyMs)
    }

    @Test
    fun `a frozen cost and its price version reach the row`() = runBlocking {
        val dao = FakeDao()
        UsageCallRecorder.record(
            ledger = ledgerFor(dao),
            usage = reportedUsage,
            context = UsageCallContext(purpose = UsagePurpose.MAIN),
            modelId = "deepseek-chat",
            costMicros = 1232,
            priceVersionId = "abcd1234",
        )

        val row = dao.rows.single()
        assertEquals(1232L, row.costMicros)
        assertEquals("abcd1234", row.priceVersionId)
    }

    @Test
    fun `an unreported usage writes nothing rather than a zero row`() = runBlocking {
        val dao = FakeDao()
        val outcome = UsageCallRecorder.record(
            ledger = ledgerFor(dao),
            usage = null,
            context = UsageCallContext(purpose = UsagePurpose.MAIN),
        )

        assertEquals(UsageCallRecorder.Outcome.NoUsage, outcome)
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `a storage failure is contained and surfaced as Failed`() = runBlocking {
        val dao = FakeDao()
        dao.failWith = IllegalStateException("ledger db is gone")
        val outcome = UsageCallRecorder.record(
            ledger = ledgerFor(dao),
            usage = reportedUsage,
            context = UsageCallContext(purpose = UsagePurpose.MAIN),
        )

        assertTrue(outcome is UsageCallRecorder.Outcome.Failed)
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `an undeclared context lands in UNKNOWN instead of hiding inside MAIN`() = runBlocking {
        val dao = FakeDao()
        UsageCallRecorder.record(
            ledger = ledgerFor(dao),
            usage = reportedUsage,
            context = UsageCallContext(),
        )

        assertEquals("UNKNOWN", dao.rows.single().purpose)
    }
}
