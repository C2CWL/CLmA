package com.clma.app.data.usage

import java.time.LocalDate
import java.time.ZoneId

/**
 * The one definition of "the last N days" for the statistics page, shared by the ledger query and
 * the day-axis fill.
 *
 * The window is N **calendar days ending today inclusive**, i.e. `[today - (N - 1) .. today]`.
 *
 * It used to be two definitions. The query cut off at `now - N * 24h`, which lands in the middle of
 * a calendar day, so it read N+1 days: a slice of the day N days ago plus the whole of today. The
 * chart's [UsageDaySeries.fillWindow] only keeps N days, so that slice was silently dropped from
 * "区间合计" and from the daily average, while "窗口总计" — the ledger total over the queried rows —
 * still contained it. Two numbers on the same card, computed from different windows, differ by up
 * to a day of usage.
 *
 * Anchoring the cutoff to local midnight removes the extra day instead of papering over it: the
 * rows read are exactly the rows the chart plots, so the total, the sum of the bars and the average
 * agree by construction.
 *
 * Pure: no Android, no Room, no coroutines.
 */
object UsageWindow {

    /** First calendar day of the window; also the oldest day the chart plots. */
    fun startDate(today: LocalDate, windowDays: Int): LocalDate =
        today.minusDays(windowDays.coerceAtLeast(1) - 1L)

    /** Local midnight opening [startDate], as epoch millis — the ledger query's `sinceMs`. */
    fun startEpochMillis(today: LocalDate, windowDays: Int, zone: ZoneId): Long =
        startDate(today, windowDays).atStartOfDay(zone).toInstant().toEpochMilli()

    /** [startEpochMillis] for the current moment; `today` is injected above for tests. */
    fun startEpochMillis(windowDays: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        startEpochMillis(LocalDate.now(zone), windowDays, zone)
}
