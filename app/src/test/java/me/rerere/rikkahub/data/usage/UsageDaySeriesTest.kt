package me.rerere.rikkahub.data.usage

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageDaySeriesTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 16)

    private fun day(key: String, tokens: Long, calls: Int = 1) = UsageStatBucket(
        key = key,
        callCount = calls,
        inputTokens = tokens,
        outputTokens = 0L,
        cacheHitTokens = 0L,
        cachePromptTokens = 0L,
        cacheReported = false,
        providerCostUsd = null,
        costMicros = null,
        measuredOutputTokens = 0L,
        generationMs = 0L,
    )

    // ---- shape ---------------------------------------------------------------------------

    @Test
    fun `an empty window fills to every day, all zero`() {
        val filled = UsageDaySeries.fillWindow(emptyList(), windowDays = 7, today = today)

        assertEquals(7, filled.size)
        assertTrue(filled.all { it.totalTokens == 0L && it.callCount == 0 })
    }

    @Test
    fun `the result is newest first and starts on today`() {
        val filled = UsageDaySeries.fillWindow(emptyList(), windowDays = 3, today = today)

        assertEquals(listOf("2026-10-16", "2026-10-15", "2026-10-14"), filled.map { it.key })
    }

    @Test
    fun `active days keep their numbers and gaps become zero days`() {
        val filled = UsageDaySeries.fillWindow(
            byDay = listOf(day("2026-10-16", 100), day("2026-10-12", 50)),
            windowDays = 5,
            today = today,
        )

        assertEquals(listOf(100L, 0L, 0L, 0L, 50L), filled.map { it.totalTokens })
        assertEquals(150L, filled.sumOf { it.totalTokens })
    }

    @Test
    fun `summing the filled series still equals the window total`() {
        val filled = UsageDaySeries.fillWindow(
            byDay = listOf(day("2026-10-10", 10), day("2026-10-15", 20), day("2026-10-16", 30)),
            windowDays = 7,
            today = today,
        )

        assertEquals(60L, filled.sumOf { it.totalTokens })
    }

    // ---- boundaries ----------------------------------------------------------------------

    @Test
    fun `the window's first day is today minus windowDays plus one`() {
        val filled = UsageDaySeries.fillWindow(
            byDay = listOf(day("2026-10-10", 10), day("2026-10-11", 20)),
            windowDays = 7,
            today = today,
        )

        // window = [10-10 .. 10-16]; both days are inside, oldest listed last.
        assertEquals("2026-10-10", filled.last().key)
        assertEquals(30L, filled.sumOf { it.totalTokens })
    }

    @Test
    fun `days older than the window are dropped`() {
        val filled = UsageDaySeries.fillWindow(
            byDay = listOf(day("2026-10-09", 99), day("2026-10-10", 10)),
            windowDays = 7,
            today = today,
        )

        assertEquals(10L, filled.sumOf { it.totalTokens })
    }

    @Test
    fun `future days from a skewed clock are dropped`() {
        val filled = UsageDaySeries.fillWindow(
            byDay = listOf(day("2026-10-17", 99), day("2026-10-16", 10)),
            windowDays = 7,
            today = today,
        )

        assertEquals(10L, filled.sumOf { it.totalTokens })
        assertTrue(filled.none { it.key == "2026-10-17" })
    }

    @Test
    fun `an unparseable key is dropped rather than crashing the chart`() {
        val filled = UsageDaySeries.fillWindow(
            byDay = listOf(day("not-a-date", 99), day("2026-10-16", 10)),
            windowDays = 2,
            today = today,
        )

        assertEquals(2, filled.size)
        assertEquals(10L, filled.sumOf { it.totalTokens })
    }

    @Test
    fun `windowDays is coerced to at least one`() {
        val filled = UsageDaySeries.fillWindow(
            byDay = listOf(day("2026-10-16", 10)),
            windowDays = 0,
            today = today,
        )

        assertEquals(listOf("2026-10-16"), filled.map { it.key })
    }

    // ---- invariants ----------------------------------------------------------------------

    @Test
    fun `a zero day claims no price, no cache rate and no throughput`() {
        val zero = UsageDaySeries.fillWindow(emptyList(), windowDays = 1, today = today).single()

        assertNull(zero.providerCostUsd)
        assertNull(zero.costMicros)
        assertNull(zero.cacheHitRate)
        assertNull(zero.tokensPerSecond)
    }
}
