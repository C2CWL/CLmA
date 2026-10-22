package me.rerere.rikkahub.ui.pages.stats

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * The statistics page's compact number formatting, extracted from `StatsPage.kt` so it can be
 * unit-tested without Compose on the classpath.
 *
 * Two correctness fixes over the ad-hoc `"%.1f".format(...)` it replaces:
 *  - the locale is pinned to [Locale.ROOT], so a device in a comma-decimal locale (de, fr, …)
 *    cannot render "1,2M" — the unit suffixes are English either way and a locale-dependent
 *    decimal separator made the same number render two ways;
 *  - the unit is chosen by the value *as it will be displayed*: the candidate is rounded at the
 *    target unit's own precision, and a candidate that rounds up to 1000 is promoted to the next
 *    unit, so 999_950 tokens becomes "1.00M" instead of the overflowing "1000.0K".
 */

private val COMPACT_SUFFIXES = listOf("", "K", "M", "B", "T")

/**
 * [count] in compact form: a plain integer below 1000, otherwise a scaled value with a suffix.
 * [decimals] picks the fraction digits per suffix index (0 = no suffix).
 */
private fun formatCompact(count: Long, decimals: (Int) -> Int): String {
    if (count < 1_000L) return count.toString()
    var divisor = 1.0
    for (unit in 1..COMPACT_SUFFIXES.lastIndex) {
        val candidate = count / (divisor * 1_000.0)
        val d = decimals(unit)
        val fits = BigDecimal.valueOf(candidate).setScale(d, RoundingMode.HALF_UP) < BigDecimal(1000)
        if (fits || unit == COMPACT_SUFFIXES.lastIndex) {
            return String.format(Locale.ROOT, "%.${d}f%s", candidate, COMPACT_SUFFIXES[unit])
        }
        divisor *= 1_000.0
    }
    error("unreachable")
}

/** Token counts: K gets one fraction digit, M and beyond get two (card width allows it). */
fun formatCompactTokens(count: Long): String = formatCompact(count) { unit ->
    when (unit) {
        1 -> 1 // K
        else -> 2 // M, B, T
    }
}

/** Plain counts (calls, messages, launches): one fraction digit is enough to compare. */
fun formatCompactCount(count: Long): String = formatCompact(count) { 1 }

/** A throughput figure; the unit ("tok/s") lives in the string resource. */
fun formatTokenRate(rate: Double): String = String.format(Locale.ROOT, "%.1f", rate)
