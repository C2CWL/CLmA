package me.rerere.rikkahub.data.usage

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageStatsViewTest {

    private val utc = ZoneId.of("UTC")

    private val day1Morning = Instant.parse("2026-10-01T09:00:00Z").toEpochMilli()
    private val day1Evening = Instant.parse("2026-10-01T21:00:00Z").toEpochMilli()
    private val day2Morning = Instant.parse("2026-10-02T03:00:00Z").toEpochMilli()

    private fun row(
        id: String,
        atMs: Long = day1Morning,
        purpose: UsagePurpose = UsagePurpose.MAIN,
        modelId: String? = "deepseek-chat",
        providerId: String? = null,
        assistantId: String? = null,
        conversationId: String? = null,
        input: Int = 0,
        output: Int = 0,
        cached: Int = 0,
        cachedReported: Boolean = false,
        providerCostUsd: Double? = null,
        costMicros: Long? = null,
        latencyMs: Long? = null,
    ) = UsageRecordEntity(
        id = id,
        createdAtMs = atMs,
        purpose = purpose.name,
        providerId = providerId,
        modelId = modelId,
        assistantId = assistantId,
        conversationId = conversationId,
        inputTokens = input,
        outputTokens = output,
        totalTokens = input + output,
        cachedTokens = cached,
        cachedTokensReported = cachedReported,
        providerCostUsd = providerCostUsd,
        costMicros = costMicros,
        latencyMs = latencyMs,
    )

    // ---- empty ---------------------------------------------------------------------------

    @Test
    fun `an empty window yields the empty view, never null`() {
        val view = UsageStatsFactory.build(emptyList(), utc)

        assertEquals(0, view.total.callCount)
        assertEquals(0L, view.total.totalTokens)
        assertNull(view.total.providerCostUsd)
        assertTrue(view.byDay.isEmpty())
        assertTrue(view.byPurpose.isEmpty())
        assertTrue(view.byProvider.isEmpty())
        assertTrue(view.byModel.isEmpty())
        assertTrue(view.byAssistant.isEmpty())
        assertEquals(0f, view.shareOfTokens(view.total), 0f)
    }

    // ---- provider ------------------------------------------------------------------------

    @Test
    fun `groups rows by provider and sums their tokens`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", providerId = "DeepSeek", input = 100, output = 10),
                row("b", providerId = "DeepSeek", input = 200, output = 20),
                row("c", providerId = "阿里云百炼", input = 50, output = 5),
            ),
            utc,
        )

        assertEquals(2, view.byProvider.size)
        val deepseek = view.byProvider.first { it.key == "DeepSeek" }
        assertEquals(2, deepseek.callCount)
        assertEquals(300L, deepseek.inputTokens)
        assertEquals(30L, deepseek.outputTokens)
        // Largest first, so the provider carrying the most tokens heads the ranking.
        assertEquals("DeepSeek", view.byProvider.first().key)
    }

    @Test
    fun `buckets a null provider id under unknown`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", providerId = null, input = 10),
                row("b", providerId = "DeepSeek", input = 100),
            ),
            utc,
        )

        assertEquals(setOf("unknown", "DeepSeek"), view.byProvider.map { it.key }.toSet())
        assertEquals(10L, view.byProvider.first { it.key == "unknown" }.inputTokens)
    }

    // ---- purpose -------------------------------------------------------------------------

    @Test
    fun `groups rows by purpose and sums their tokens`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", purpose = UsagePurpose.MAIN, input = 100, output = 10),
                row("b", purpose = UsagePurpose.MAIN, input = 200, output = 20),
                row("c", purpose = UsagePurpose.TOOL_LOOP, input = 50, output = 5),
            ),
            utc,
        )

        assertEquals(2, view.byPurpose.size)
        val main = view.byPurpose.first { it.key == "MAIN" }
        assertEquals(2, main.callCount)
        assertEquals(300L, main.inputTokens)
        assertEquals(30L, main.outputTokens)
        assertEquals(330L, main.totalTokens)
    }

    @Test
    fun `ranks purposes largest first by total tokens`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", purpose = UsagePurpose.MAIN, input = 10, output = 0),
                row("b", purpose = UsagePurpose.SUBAGENT, input = 900, output = 0),
                row("c", purpose = UsagePurpose.COMPACTION, input = 100, output = 0),
            ),
            utc,
        )

        assertEquals(listOf("SUBAGENT", "COMPACTION", "MAIN"), view.byPurpose.map { it.key })
    }

    @Test
    fun `keeps every purpose including the auxiliary ones`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", purpose = UsagePurpose.TITLE),
                row("b", purpose = UsagePurpose.SUGGESTION),
            ),
            utc,
        )

        // Unlike the turn view, the stats page is where the auxiliary spend is meant to be seen.
        assertEquals(setOf("TITLE", "SUGGESTION"), view.byPurpose.map { it.key }.toSet())
    }

    // ---- model ---------------------------------------------------------------------------

    @Test
    fun `buckets a null model id under unknown`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", modelId = null, input = 1),
                row("b", modelId = "gpt-x", input = 2),
            ),
            utc,
        )

        assertEquals(setOf("unknown", "gpt-x"), view.byModel.map { it.key }.toSet())
        assertEquals(1L, view.byModel.first { it.key == "unknown" }.inputTokens)
    }

    // ---- day -----------------------------------------------------------------------------

    @Test
    fun `buckets rows by local day and sorts newest first`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", atMs = day1Morning, input = 10),
                row("b", atMs = day1Evening, input = 20),
                row("c", atMs = day2Morning, input = 30),
            ),
            utc,
        )

        assertEquals(listOf("2026-10-02", "2026-10-01"), view.byDay.map { it.key })
        val yesterday = view.byDay.first { it.key == "2026-10-01" }
        assertEquals(2, yesterday.callCount)
        assertEquals(30L, yesterday.inputTokens)
    }

    @Test
    fun `a call after midnight lands in the next day`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", atMs = Instant.parse("2026-10-01T23:59:59Z").toEpochMilli(), input = 1),
                row("b", atMs = Instant.parse("2026-10-02T00:00:01Z").toEpochMilli(), input = 2),
            ),
            utc,
        )

        assertEquals(2, view.byDay.size)
        assertEquals(1L, view.byDay.first { it.key == "2026-10-01" }.inputTokens)
        assertEquals(2L, view.byDay.first { it.key == "2026-10-02" }.inputTokens)
    }

    @Test
    fun `the day bucket follows the injected zone, not a fixed one`() {
        val instant = Instant.parse("2026-10-01T20:00:00Z").toEpochMilli()
        val east = UsageStatsFactory.build(listOf(row("a", atMs = instant)), ZoneId.of("Asia/Shanghai"))

        // 20:00Z is already 2026-10-02 in UTC+8.
        assertEquals("2026-10-02", east.byDay.single().key)
    }

    // ---- assistant -----------------------------------------------------------------------

    @Test
    fun `keeps the row's own assistant id`() {
        val view = UsageStatsFactory.build(
            listOf(row("a", assistantId = "asst-A", conversationId = "conv-1")),
            utc,
        )

        assertEquals("asst-A", view.byAssistant.single().key)
    }

    @Test
    fun `falls back to the conversation's assistant when the row has none`() {
        val view = UsageStatsFactory.build(
            listOf(row("a", assistantId = null, conversationId = "conv-1")),
            utc,
            assistantOfConversation = { if (it == "conv-1") "asst-from-conv" else null },
        )

        assertEquals("asst-from-conv", view.byAssistant.single().key)
    }

    @Test
    fun `buckets under unknown when neither the row nor its conversation resolves`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", assistantId = null, conversationId = null),
                row("b", assistantId = null, conversationId = "conv-x"),
            ),
            utc,
            assistantOfConversation = { null },
        )

        assertEquals("unknown", view.byAssistant.single().key)
        assertEquals(2, view.byAssistant.single().callCount)
    }

    @Test
    fun `does not consult the conversation resolver when the row has its own assistant`() {
        var consulted = 0
        val view = UsageStatsFactory.build(
            listOf(row("a", assistantId = "asst-A", conversationId = "conv-1")),
            utc,
            assistantOfConversation = { consulted++; null },
        )

        assertEquals(0, consulted)
        assertEquals("asst-A", view.byAssistant.single().key)
    }

    // ---- cost ----------------------------------------------------------------------------

    @Test
    fun `sums a provider reported cost`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", providerCostUsd = 0.25),
                row("b", providerCostUsd = 0.75),
            ),
            utc,
        )

        assertEquals(1.0, view.total.providerCostUsd!!, 1e-9)
    }

    @Test
    fun `keeps a missing price null instead of summing it as zero`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", providerCostUsd = 0.5),
                row("b", providerCostUsd = null),
            ),
            utc,
        )

        assertEquals(0.5, view.total.providerCostUsd!!, 1e-9)
        assertNull(view.total.costMicros)
    }

    @Test
    fun `all null prices stay null, never zero`() {
        val view = UsageStatsFactory.build(listOf(row("a"), row("b")), utc)

        assertNull(view.total.providerCostUsd)
        assertNull(view.total.costMicros)
        assertFalse(view.total.estimatedCostOnly)
    }

    @Test
    fun `sums cost micros separately from the provider cost`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", costMicros = 1_000L),
                row("b", costMicros = 2_500L),
                row("c", costMicros = null),
            ),
            utc,
        )

        assertEquals(3_500L, view.total.costMicros)
    }

    @Test
    fun `estimated cost only is true for a table computed price`() {
        val tableOnly = UsageStatsFactory.build(listOf(row("a", costMicros = 12L)), utc)
        val reported = UsageStatsFactory.build(
            listOf(row("a", providerCostUsd = 0.01, costMicros = 12L)),
            utc,
        )

        assertTrue(tableOnly.total.estimatedCostOnly)
        assertFalse(reported.total.estimatedCostOnly)
    }

    // ---- cache ---------------------------------------------------------------------------

    @Test
    fun `cache totals sum only the rows that reported cache fields`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", input = 100, cached = 40, cachedReported = true),
                row("b", input = 900, cached = 0, cachedReported = false),
            ),
            utc,
        )

        // The silent row's 900 input tokens must not enter the denominator.
        assertEquals(40L, view.total.cacheHitTokens)
        assertEquals(100L, view.total.cachePromptTokens)
        assertEquals(40, view.total.cacheHitRate)
    }

    @Test
    fun `cache hit rate is null when nothing reported cache`() {
        val view = UsageStatsFactory.build(listOf(row("a", input = 500)), utc)

        assertFalse(view.total.cacheReported)
        assertNull(view.total.cacheHitRate)
    }

    @Test
    fun `cache hit rate is null when a report has no prompt tokens`() {
        val view = UsageStatsFactory.build(
            listOf(row("a", input = 0, cached = 0, cachedReported = true)),
            utc,
        )

        assertTrue(view.total.cacheReported)
        assertNull(view.total.cacheHitRate)
    }

    // ---- total & share -------------------------------------------------------------------

    @Test
    fun `the total aggregates every row`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", input = 100, output = 10),
                row("b", input = 200, output = 20),
                row("c", input = 300, output = 30),
            ),
            utc,
        )

        assertEquals(3, view.total.callCount)
        assertEquals(600L, view.total.inputTokens)
        assertEquals(60L, view.total.outputTokens)
        assertEquals(660L, view.total.totalTokens)
    }

    @Test
    fun `share of tokens is relative to the window total`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", input = 300, output = 0),
                row("b", input = 700, output = 0),
            ),
            utc,
        )

        val big = view.byPurpose.single() // same purpose ⇒ one bucket
        assertEquals(1f, view.shareOfTokens(big), 1e-6f)

        val split = UsageStatsFactory.build(
            listOf(
                row("a", purpose = UsagePurpose.MAIN, input = 250, output = 0),
                row("b", purpose = UsagePurpose.SUBAGENT, input = 750, output = 0),
            ),
            utc,
        )
        assertEquals(0.25f, split.shareOfTokens(split.byPurpose.first { it.key == "MAIN" }), 1e-6f)
        assertEquals(0.75f, split.shareOfTokens(split.byPurpose.first { it.key == "SUBAGENT" }), 1e-6f)
    }

    // ---- D6: throughput ---------------------------------------------------------------

    @Test
    fun `a bucket's rate is measured output over the latencies that were reported`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", output = 100, latencyMs = 2_000),
                row("b", output = 100, latencyMs = 3_000),
            ),
            utc,
        )

        assertEquals(200L, view.total.measuredOutputTokens)
        assertEquals(5_000L, view.total.generationMs)
        assertEquals(40.0, view.total.tokensPerSecond!!, 1e-6)
    }

    @Test
    fun `an unmeasured call is in neither the numerator nor the denominator`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("measured", output = 50, latencyMs = 1_000),
                row("unmeasured", output = 9_999, latencyMs = null),
            ),
            utc,
        )

        assertEquals(50L, view.total.measuredOutputTokens)
        assertEquals(1_000L, view.total.generationMs)
        assertEquals(50.0, view.total.tokensPerSecond!!, 1e-6)
    }

    @Test
    fun `a bucket nobody measured reports no rate instead of zero`() {
        val view = UsageStatsFactory.build(listOf(row("a", output = 100)), utc)

        assertEquals(0L, view.total.generationMs)
        assertNull(view.total.tokensPerSecond)
    }

    @Test
    fun `a reported zero latency cannot divide`() {
        val view = UsageStatsFactory.build(listOf(row("a", output = 100, latencyMs = 0)), utc)

        assertEquals(0L, view.total.generationMs)
        assertEquals(100L, view.total.measuredOutputTokens)
        assertNull(view.total.tokensPerSecond)
    }

    @Test
    fun `the by-model ranking carries the rate too`() {
        val view = UsageStatsFactory.build(
            listOf(
                row("a", modelId = "deepseek-chat", output = 100, latencyMs = 2_000),
                row("b", modelId = "deepseek-chat", output = 100, latencyMs = 2_000),
                row("c", modelId = "qwen3.8-omni-flash", output = 30, latencyMs = 1_000),
            ),
            utc,
        )

        val deepseek = view.byModel.first { it.key == "deepseek-chat" }
        assertEquals(50.0, deepseek.tokensPerSecond!!, 1e-6)
    }
}
