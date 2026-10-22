package com.clma.app.data.usage

import com.clma.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTurnViewTest {

    @Test
    fun `tokens per second uses only the calls that reported a latency`() {
        val view = TurnUsageView(
            calls = listOf(
                call(output = 100, latencyMs = 2_000),
                call(output = 50, latencyMs = 1_000),
                call(output = 999, latencyMs = null),
            ),
            inputTokens = 0,
            outputTokens = 1_149,
            cacheHitTokens = 0,
            cachePromptTokens = 0,
            cacheReported = false,
            providerCostUsd = null,
            costMicros = null,
        )

        assertEquals(150L, view.measuredOutputTokens)
        assertEquals(3_000L, view.generationMs)
        assertEquals(50.0, view.tokensPerSecond!!, 1e-9)
    }

    @Test
    fun `tokens per second is null when no call reported a latency`() {
        val view = TurnUsageView(
            calls = listOf(call(output = 10, latencyMs = null)),
            inputTokens = 0,
            outputTokens = 10,
            cacheHitTokens = 0,
            cachePromptTokens = 0,
            cacheReported = false,
            providerCostUsd = null,
            costMicros = null,
        )

        assertNull(view.tokensPerSecond)
    }

    private fun call(output: Int, latencyMs: Long?): UsageCallView = UsageCallView(
        purpose = UsagePurpose.MAIN.name,
        inputTokens = 0,
        outputTokens = output,
        cachedTokens = 0,
        cachedTokensReported = false,
        providerCostUsd = null,
        costMicros = null,
        latencyMs = latencyMs,
        streaming = true,
        atMs = 0L,
    )

    private fun row(
        id: String,
        atMs: Long,
        purpose: UsagePurpose = UsagePurpose.MAIN,
        input: Int = 0,
        output: Int = 0,
        cached: Int = 0,
        cachedReported: Boolean = false,
        providerCostUsd: Double? = null,
        costMicros: Long? = null,
        latencyMs: Long? = null,
        streaming: Boolean = false,
    ) = UsageRecordEntity(
        id = id,
        createdAtMs = atMs,
        purpose = purpose.name,
        inputTokens = input,
        outputTokens = output,
        totalTokens = input + output,
        cachedTokens = cached,
        cachedTokensReported = cachedReported,
        providerCostUsd = providerCostUsd,
        costMicros = costMicros,
        latencyMs = latencyMs,
        streaming = streaming,
    )

    // ---- windows -------------------------------------------------------------------------

    @Test
    fun `windows end where the next node begins and the last is open-ended`() {
        val windows = UsageTurnViewFactory.windowsFor(
            listOf("m1" to 100L, "m2" to 500L, "m3" to 900L)
        )

        assertEquals(3, windows.size)
        assertEquals("m1", windows[0].first)
        assertEquals(UsageTurnWindow(100L, 500L), windows[0].second)
        assertEquals(UsageTurnWindow(500L, 900L), windows[1].second)
        assertEquals(UsageTurnWindow(900L, Long.MAX_VALUE), windows[2].second)
    }

    @Test
    fun `a non-increasing start becomes an empty window, never a reversed one`() {
        val windows = UsageTurnViewFactory.windowsFor(listOf("m1" to 100L, "m2" to 100L))

        // m1 loses its share; m2 still gets everything from 100 on.
        assertEquals(UsageTurnWindow(100L, 100L), windows[0].second)
        assertFalse(windows[0].second.contains(100L))
        assertEquals(UsageTurnWindow(100L, Long.MAX_VALUE), windows[1].second)
        assertTrue(windows[1].second.contains(100L))
    }

    // ---- purpose filter ------------------------------------------------------------------

    @Test
    fun `title and suggestion are not part of a turn`() {
        assertFalse(UsageTurnViewFactory.isTurnCall(UsagePurpose.TITLE.name))
        assertFalse(UsageTurnViewFactory.isTurnCall(UsagePurpose.SUGGESTION.name))
    }

    @Test
    fun `every other purpose stays in the turn`() {
        val kept = listOf(
            UsagePurpose.MAIN,
            UsagePurpose.TOOL_LOOP,
            UsagePurpose.COMPACTION,
            UsagePurpose.MEMORY_EXTRACT,
            UsagePurpose.SUBAGENT,
            UsagePurpose.CRON,
            UsagePurpose.WORKFLOW,
            UsagePurpose.SKILL_TEST,
            UsagePurpose.TRANSLATION,
            UsagePurpose.UNKNOWN,
        )
        for (purpose in kept) {
            assertTrue(purpose.name, UsageTurnViewFactory.isTurnCall(purpose.name))
        }
    }

    // ---- aggregation ---------------------------------------------------------------------

    @Test
    fun `aggregate sums every call and lists them oldest first`() {
        val view = UsageTurnViewFactory.aggregate(
            listOf(
                row("b", 200L, input = 30, output = 5),
                row("a", 100L, input = 10, output = 2),
            )
        )

        assertEquals(2, view.callCount)
        assertEquals(listOf(100L, 200L), view.calls.map { it.atMs })
        assertEquals(40L, view.inputTokens)
        assertEquals(7L, view.outputTokens)
    }

    @Test
    fun `cache rate only counts rows that reported cache fields`() {
        val view = UsageTurnViewFactory.aggregate(
            listOf(
                // Reports nothing about cache: its 1000 input tokens must not enter the rate.
                row("a", 100L, input = 1000, output = 1, cached = 0, cachedReported = false),
                row("b", 200L, input = 100, output = 1, cached = 80, cachedReported = true),
                row("c", 300L, input = 50, output = 1, cached = 30, cachedReported = true),
            )
        )

        assertTrue(view.cacheReported)
        assertEquals(110L, view.cacheHitTokens)
        assertEquals(150L, view.cachePromptTokens)
        // The unreported row still counts toward the raw input total.
        assertEquals(1150L, view.inputTokens)
    }

    @Test
    fun `cache is absent when no row reported it`() {
        val view = UsageTurnViewFactory.aggregate(
            listOf(row("a", 100L, input = 10, output = 1, cached = 0, cachedReported = false))
        )

        assertFalse(view.cacheReported)
        assertEquals(0L, view.cacheHitTokens)
        assertEquals(0L, view.cachePromptTokens)
    }

    @Test
    fun `costs sum only over the rows that carry them`() {
        val view = UsageTurnViewFactory.aggregate(
            listOf(
                row("a", 100L, providerCostUsd = 0.001, costMicros = 1000L),
                row("b", 200L, providerCostUsd = null, costMicros = 250L),
            )
        )

        assertEquals(0.001, view.providerCostUsd!!, 1e-12)
        assertEquals(1250L, view.costMicros)
        assertFalse(view.estimatedCostOnly)
    }

    @Test
    fun `a turn with only a computed price is estimated`() {
        val view = UsageTurnViewFactory.aggregate(
            listOf(row("a", 100L, providerCostUsd = null, costMicros = 700L))
        )

        assertNull(view.providerCostUsd)
        assertEquals(700L, view.costMicros)
        assertTrue(view.estimatedCostOnly)
    }

    @Test
    fun `an unpriced turn stays null, never zero`() {
        val view = UsageTurnViewFactory.aggregate(listOf(row("a", 100L)))

        assertNull(view.providerCostUsd)
        assertNull(view.costMicros)
        assertFalse(view.estimatedCostOnly)
    }

    // ---- window assignment ---------------------------------------------------------------

    @Test
    fun `a row on a boundary belongs to the later window only`() {
        val windows = UsageTurnViewFactory.windowsFor(listOf("m1" to 100L, "m2" to 500L))
        val records = listOf(
            row("early", 100L),
            row("onBoundary", 500L),
            row("late", 900L),
        )

        val byMessage = UsageTurnViewFactory.map(windows, records)

        assertEquals(listOf(100L), byMessage.getValue("m1").calls.map { it.atMs })
        assertEquals(listOf(500L, 900L), byMessage.getValue("m2").calls.map { it.atMs })
        assertEquals(1, byMessage.getValue("m1").callCount)
        assertEquals(2, byMessage.getValue("m2").callCount)
    }

    @Test
    fun `title and suggestion rows in the window are dropped`() {
        val windows = UsageTurnViewFactory.windowsFor(listOf("m1" to 100L, "m2" to 500L))
        val records = listOf(
            row("main", 120L, input = 10, output = 1),
            row("title", 300L, purpose = UsagePurpose.TITLE, input = 999, output = 9),
            row("suggestion", 400L, purpose = UsagePurpose.SUGGESTION, input = 999, output = 9),
        )

        val view = UsageTurnViewFactory.map(windows, records).getValue("m1")

        assertEquals(1, view.callCount)
        assertEquals(10L, view.inputTokens)
    }

    @Test
    fun `a window with no rows is absent from the map`() {
        val windows = UsageTurnViewFactory.windowsFor(listOf("m1" to 100L, "m2" to 500L))

        assertTrue(UsageTurnViewFactory.map(windows, emptyList()).isEmpty())
        assertNull(UsageTurnViewFactory.turnUsageFor(emptyList(), windows[0].second))
    }

    // ---- D6 footers ----------------------------------------------------------------------

    @Test
    fun `a footer spans the user message that opened the turn to the reply's finish`() {
        val nodes = listOf(
            TurnNode("u1", MessageRole.USER, createdAtMs = 1_000L, finishedAtMs = null),
            TurnNode("a1", MessageRole.ASSISTANT, createdAtMs = 1_200L, finishedAtMs = 6_000L),
        )
        val records = listOf(row("m1", 1_500L, input = 10, output = 4))

        val footer = UsageTurnViewFactory.footersFor(nodes, records).getValue("a1")

        assertEquals(1_000L, footer.startedAtMs)
        assertEquals(6_000L, footer.finishedAtMs)
        assertFalse(footer.running)
        assertEquals(10L, footer.usage!!.inputTokens)
    }

    @Test
    fun `a finished turn is not stretched by the next user message`() {
        val nodes = listOf(
            TurnNode("u1", MessageRole.USER, 1_000L, null),
            TurnNode("a1", MessageRole.ASSISTANT, 1_200L, 6_000L),
            TurnNode("u2", MessageRole.USER, 90_000L, null),
        )
        val records = listOf(row("m1", 1_500L))

        val footer = UsageTurnViewFactory.footersFor(nodes, records).getValue("a1")

        // 6s, not the ~89s that ending at the next node would have produced.
        assertEquals(6_000L, footer.finishedAtMs)
    }

    @Test
    fun `the running turn gets a footer before its first row lands`() {
        val nodes = listOf(
            TurnNode("u1", MessageRole.USER, 1_000L, null),
            TurnNode("a1", MessageRole.ASSISTANT, 1_200L, null),
        )

        val footer = UsageTurnViewFactory
            .footersFor(nodes, emptyList(), liveMessageId = "a1")
            .getValue("a1")

        assertTrue(footer.running)
        assertNull(footer.usage)
        assertNull(footer.finishedAtMs)
        assertEquals(1_000L, footer.startedAtMs)
    }

    @Test
    fun `a running turn keeps the rows that have already landed`() {
        val nodes = listOf(
            TurnNode("u1", MessageRole.USER, 1_000L, null),
            TurnNode("a1", MessageRole.ASSISTANT, 1_200L, 9_000L),
        )
        val records = listOf(row("m1", 1_500L, input = 10, output = 1))

        val footer = UsageTurnViewFactory
            .footersFor(nodes, records, liveMessageId = "a1")
            .getValue("a1")

        // Still running ignores the stale finish stamped by the last completed call.
        assertTrue(footer.running)
        assertNull(footer.finishedAtMs)
        assertEquals(1, footer.usage!!.callCount)
    }

    @Test
    fun `an interrupted half carries its usage into the final reply`() {
        val nodes = listOf(
            TurnNode("u1", MessageRole.USER, 1_000L, null),
            // 被插话打断的前半：没有 finalize，finishedAt 保持 null。
            TurnNode("a1", MessageRole.ASSISTANT, 1_200L, null),
            TurnNode("u2", MessageRole.USER, 5_000L, null),
            TurnNode("a2", MessageRole.ASSISTANT, 5_200L, 9_000L),
        )
        val records = listOf(
            row("m1", 1_500L, input = 10, output = 4),
            row("m2", 5_500L, input = 20, output = 8),
        )

        val footers = UsageTurnViewFactory.footersFor(nodes, records)

        assertFalse(footers.containsKey("a1"))
        val footer = footers.getValue("a2")
        assertEquals(30L, footer.usage!!.inputTokens)
        assertEquals(12L, footer.usage.outputTokens)
        assertEquals(2, footer.usage.callCount)
    }

    @Test
    fun `a node with no rows that is not running gets no footer`() {
        val nodes = listOf(
            TurnNode("u1", MessageRole.USER, 1_000L, null),
            TurnNode("a1", MessageRole.ASSISTANT, 1_200L, 6_000L),
        )

        assertTrue(UsageTurnViewFactory.footersFor(nodes, emptyList()).isEmpty())
    }

    @Test
    fun `user nodes never get a footer`() {
        val nodes = listOf(TurnNode("u1", MessageRole.USER, 1_000L, 2_000L))

        assertTrue(UsageTurnViewFactory.footersFor(nodes, emptyList(), liveMessageId = "u1").isEmpty())
    }
}
