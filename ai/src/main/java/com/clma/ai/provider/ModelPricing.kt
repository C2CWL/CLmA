package me.rerere.ai.provider

import kotlinx.serialization.Serializable

/**
 * P2-11d - an explicit, user-owned price row for one model, per million tokens.
 *
 * Prices are deliberately NOT shipped as built-in data: a hard-coded table goes stale and a
 * wrong number is worse than no number. This row is what the model provider settings page
 * edits; when it is absent the ledger falls back to the per-token metadata a provider
 * publishes (see [Model.pricePromptPerToken]), and when neither exists the cost stays null,
 * because an unpriced call is unknown and must never be recorded as free.
 *
 * The rate that actually applied to a call is frozen into the ledger row at write time, so
 * editing this row later cannot rewrite history.
 */
@Serializable
data class ModelPricing(
    /** Peak (or only) input rate, per million tokens. Cache hits are billed separately. */
    val inputPerMillion: Double? = null,
    val outputPerMillion: Double? = null,
    /** Cached input reads; well below [inputPerMillion] at every provider that reports them. */
    val cacheHitPerMillion: Double? = null,
    /** Cache writes, where a provider charges for them at all. */
    val cacheWritePerMillion: Double? = null,
    /**
     * Discounted rates that apply outside every window in [peakWindows]. With no windows
     * configured these rates apply around the clock, which is why the settings UI pairs an
     * off-peak row with at least one window.
     */
    val offPeak: OffPeakRates? = null,
    /** When the base (peak) rates apply. Empty plus [offPeak] means off-peak always. */
    val peakWindows: List<PeakWindow> = emptyList(),
)

/** The discounted half of [ModelPricing]. */
@Serializable
data class OffPeakRates(
    val inputPerMillion: Double? = null,
    val outputPerMillion: Double? = null,
    val cacheHitPerMillion: Double? = null,
    val cacheWritePerMillion: Double? = null,
)

/**
 * One recurring peak interval, expressed on a local clock instead of a time zone id.
 *
 * A stored offset rather than an IANA zone keeps the resolver pure integer arithmetic with
 * no calendar library, which matters on Android below the java.time desugaring threshold.
 * Providers price on whole local clocks (DeepSeek bills on Beijing time), so this is
 * accurate for the real cases; a DST-observing zone would need revisiting.
 */
@Serializable
data class PeakWindow(
    /** Minutes from local midnight, inclusive. */
    val startMinute: Int,
    /** Minutes from local midnight, exclusive. */
    val endMinute: Int,
    /** Local clock offset from UTC in minutes; Beijing is 480. */
    val zoneOffsetMinutes: Int = 0,
    /** ISO day numbers this window covers, 1 = Monday .. 7 = Sunday. Empty means every day. */
    val daysOfWeek: Set<Int> = emptySet(),
)
