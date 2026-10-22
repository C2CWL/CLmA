package me.rerere.rikkahub.data.usage

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ModelPricing
import me.rerere.ai.provider.PeakWindow
import java.security.MessageDigest

/** The four rates that decide what one call costs, per million tokens. */
data class PriceRates(
    val inputPerMillion: Double?,
    val outputPerMillion: Double?,
    val cacheHitPerMillion: Double?,
    val cacheWritePerMillion: Double?,
) {
    /** True when the row carries no number at all, which means unpriced rather than free. */
    val isEmpty: Boolean
        get() = inputPerMillion == null && outputPerMillion == null &&
            cacheHitPerMillion == null && cacheWritePerMillion == null
}

/** Which part of the pricing row produced the rates that were applied. */
enum class PriceBranch {
    /** Inside a configured peak window. */
    PEAK,

    /** Outside every configured peak window. */
    OFF_PEAK,

    /** A row without off-peak rates: the same rate around the clock. */
    UNIFORM,

    /** No row for this model; the provider-published per-token metadata was used. */
    METADATA,
}

data class ResolvedPrice(val rates: PriceRates, val branch: PriceBranch)

/**
 * P2-11d - turns pricing plus a timestamp into the rates that apply, and those rates plus a
 * token report into a cost.
 *
 * Pure integer and double arithmetic on purpose: no calendar library, no clock, no storage
 * access. Everything here is decided by its arguments, which is what makes it testable and
 * what makes the frozen-on-write cost in the ledger reproducible.
 */
object UsagePriceResolver {

    private const val PER_MILLION = 1_000_000.0
    private const val MINUTES_PER_DAY = 24 * 60
    private const val MS_PER_DAY = 86_400_000L
    private const val MS_PER_MINUTE = 60_000L

    /**
     * The rates that applied at [atEpochMs].
     *
     * Returns null when nothing is priced, because an unknown cost must stay unknown. The
     * metadata fallback exists so that a model whose provider publishes per-token prices is
     * priced without the user typing anything.
     */
    fun ratesAt(
        pricing: ModelPricing?,
        atEpochMs: Long,
        fallbackInputPerToken: Double? = null,
        fallbackOutputPerToken: Double? = null,
    ): ResolvedPrice? {
        if (pricing != null) {
            val base = PriceRates(
                inputPerMillion = pricing.inputPerMillion,
                outputPerMillion = pricing.outputPerMillion,
                cacheHitPerMillion = pricing.cacheHitPerMillion,
                cacheWritePerMillion = pricing.cacheWritePerMillion,
            )
            val offPeak = pricing.offPeak
                ?: return ResolvedPrice(base, PriceBranch.UNIFORM)
            val isPeak = pricing.peakWindows.any { it.contains(atEpochMs) }
            if (isPeak) return ResolvedPrice(base, PriceBranch.PEAK)
            // A partial off-peak row inherits the peak number it does not override, so a
            // discount on output tokens alone cannot accidentally zero the input rate.
            return ResolvedPrice(
                PriceRates(
                    inputPerMillion = offPeak.inputPerMillion ?: pricing.inputPerMillion,
                    outputPerMillion = offPeak.outputPerMillion ?: pricing.outputPerMillion,
                    cacheHitPerMillion = offPeak.cacheHitPerMillion ?: pricing.cacheHitPerMillion,
                    cacheWritePerMillion = offPeak.cacheWritePerMillion ?: pricing.cacheWritePerMillion,
                ),
                PriceBranch.OFF_PEAK,
            )
        }

        val input = fallbackInputPerToken?.times(PER_MILLION)
        val output = fallbackOutputPerToken?.times(PER_MILLION)
        if (input == null && output == null) return null
        return ResolvedPrice(
            PriceRates(
                inputPerMillion = input,
                outputPerMillion = output,
                cacheHitPerMillion = null,
                cacheWritePerMillion = null,
            ),
            PriceBranch.METADATA,
        )
    }

    /**
     * Cost in millionths of the configured currency, or null when the model is unpriced.
     *
     * Cache hits and cache writes are both counted inside `promptTokens` (that is the
     * invariant P2-11a/b established: Claude is normalised to `input + cacheRead +
     * cacheWrite`, and the OpenAI/DeepSeek dialects report details inside the prompt), so
     * they are subtracted from the billable input before charging, and an unset cache rate
     * falls back to the input rate rather than to free.
     *
     * A partially filled row therefore under-reports: an absent rate charges nothing for that
     * component. The settings UI warns about that, and the alternative - refusing to cost the
     * call at all - would hide the numbers the user did enter.
     */
    fun costMicros(usage: TokenUsage, rates: PriceRates?): Long? {
        if (rates == null || rates.isEmpty) return null
        val cacheRead = usage.cachedTokens.coerceAtLeast(0)
        val cacheWrite = (usage.cacheWriteTokens ?: 0).coerceAtLeast(0)
        val billableInput = (usage.promptTokens - cacheRead - cacheWrite).coerceAtLeast(0)

        val inputRate = rates.inputPerMillion ?: 0.0
        val cacheReadRate = rates.cacheHitPerMillion ?: inputRate
        val cacheWriteRate = rates.cacheWritePerMillion ?: inputRate
        val outputRate = rates.outputPerMillion ?: 0.0

        val cost = (
            billableInput * inputRate +
                cacheRead * cacheReadRate +
                cacheWrite * cacheWriteRate +
                usage.completionTokens * outputRate
            ) / PER_MILLION
        return Math.round(cost * PER_MILLION)
    }

    /**
     * A short, stable id for the rates that were applied, frozen into the ledger row.
     *
     * It is derived from content, not from a counter, so it changes exactly when a number
     * that affected the call changes, and two calls priced the same way share it. Nothing has
     * to remember to bump a version when prices are edited.
     */
    fun priceVersionId(resolved: ResolvedPrice?): String? {
        if (resolved == null) return null
        val rates = resolved.rates
        val canonical = listOf(
            resolved.branch.name,
            rates.inputPerMillion,
            rates.outputPerMillion,
            rates.cacheHitPerMillion,
            rates.cacheWritePerMillion,
        ).joinToString("|")
        return sha1Hex(canonical).take(12)
    }

    private fun PeakWindow.contains(atEpochMs: Long): Boolean {
        val localMs = atEpochMs + zoneOffsetMinutes * MS_PER_MINUTE
        val dayIndex = Math.floorDiv(localMs, MS_PER_DAY)
        val minuteOfDay = ((localMs - dayIndex * MS_PER_DAY) / MS_PER_MINUTE).toInt()
        if (daysOfWeek.isNotEmpty()) {
            // 1970-01-01 was a Thursday, so +3 lands on ISO Monday-first day numbers.
            val isoDay = (((dayIndex + 3) % 7 + 7) % 7 + 1).toInt()
            if (isoDay !in daysOfWeek) return false
        }
        // A window whose end precedes its start wraps past local midnight.
        return if (startMinute <= endMinute) {
            minuteOfDay >= startMinute && minuteOfDay < endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    private fun sha1Hex(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { String.format("%02x", it.toInt() and 0xff) }
}
