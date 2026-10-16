package me.rerere.rikkahub.data.usage

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Turns the ledger's sparse day ranking into a continuous calendar series for the trend charts.
 *
 * [UsageStatsFactory] buckets only days that actually have rows, because a *ranking* should not
 * list days with nothing to rank. A *trend chart* needs the opposite: a day with no calls is a
 * zero, and leaving it out silently stitches the active days together, so a user who ran the app
 * twice last month reads a smooth "trend" that never happened. The same gap also made the chart
 * card's "daily average" divide by the active-day count instead of the window length, which
 * systematically overstated it.
 *
 * Pure: no Room, no Android, no coroutines — the same contract as [UsageStatsFactory].
 */
object UsageDaySeries {

    /**
     * Returns one bucket per calendar day in the window `[today - (windowDays - 1), today]`,
     * newest first (the same ordering [UsageStatsFactory.build] emits for `byDay`).
     *
     * Days with no rows get a zero bucket, so summing the result still equals the window total.
     * Buckets keyed outside the window — early rows when the query's millisecond cutoff lands
     * mid-day, or future dates from a skewed clock — are dropped: a day-aligned axis has no
     * honest place for them. Unparseable keys are dropped as well.
     *
     * @param byDay sparse day buckets, keys are ISO `yyyy-MM-dd`, any order.
     * @param windowDays window length in days; coerced to at least 1.
     * @param today the day the window ends on, injected so tests are deterministic.
     */
    fun fillWindow(
        byDay: List<UsageStatBucket>,
        windowDays: Int,
        today: LocalDate,
    ): List<UsageStatBucket> {
        val days = windowDays.coerceAtLeast(1)
        val start = today.minusDays(days - 1L)

        val active = HashMap<String, UsageStatBucket>(byDay.size)
        for (bucket in byDay) {
            val date = bucket.key.toLocalDateOrNull() ?: continue
            if (date.isBefore(start) || date.isAfter(today)) continue
            active[bucket.key] = bucket
        }

        return (0L until days.toLong()).map { offset ->
            val date = today.minusDays(offset)
            val key = date.toString()
            active[key] ?: emptyDay(key)
        }
    }

    /**
     * A zero bucket for one day. Every nullable stays null, so the ledger's invariants survive
     * the fill: an empty day claims no price, no cache rate and no throughput rather than a
     * made-up zero (see [UsageStatBucket]).
     */
    fun emptyDay(key: String): UsageStatBucket = UsageStatBucket(
        key = key,
        callCount = 0,
        inputTokens = 0L,
        outputTokens = 0L,
        cacheHitTokens = 0L,
        cachePromptTokens = 0L,
        cacheReported = false,
        providerCostUsd = null,
        costMicros = null,
        measuredOutputTokens = 0L,
        generationMs = 0L,
    )

    private fun String.toLocalDateOrNull(): LocalDate? = try {
        LocalDate.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }
}
