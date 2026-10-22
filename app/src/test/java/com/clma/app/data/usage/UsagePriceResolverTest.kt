package me.rerere.rikkahub.data.usage

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ModelPricing
import me.rerere.ai.provider.OffPeakRates
import me.rerere.ai.provider.PeakWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsagePriceResolverTest {

    /** DeepSeek shape: Beijing time, weekday 09:00-12:00 and 14:00-18:00 are peak. */
    private val deepSeek = ModelPricing(
        inputPerMillion = 2.0,
        outputPerMillion = 8.0,
        cacheHitPerMillion = 0.04,
        offPeak = OffPeakRates(
            inputPerMillion = 1.0,
            outputPerMillion = 4.0,
            cacheHitPerMillion = 0.02,
        ),
        peakWindows = listOf(
            PeakWindow(9 * 60, 12 * 60, zoneOffsetMinutes = 480, daysOfWeek = setOf(1, 2, 3, 4, 5)),
            PeakWindow(14 * 60, 18 * 60, zoneOffsetMinutes = 480, daysOfWeek = setOf(1, 2, 3, 4, 5)),
        ),
    )

    /** 2026-10-02 is a Friday, 2026-10-03 a Saturday, 2026-10-05 a Monday. */
    private fun utc(day: Int, hour: Int, minute: Int): Long = java.time.LocalDateTime
        .of(2026, 10, day, hour, minute)
        .toInstant(java.time.ZoneOffset.UTC)
        .toEpochMilli()

    @Test
    fun `a weekday inside the configured window is peak`() {
        val resolved = UsagePriceResolver.ratesAt(deepSeek, utc(2, 1, 30))!!
        assertEquals(PriceBranch.PEAK, resolved.branch)
        assertEquals(2.0, resolved.rates.inputPerMillion!!, 1e-9)
        assertEquals(8.0, resolved.rates.outputPerMillion!!, 1e-9)
    }

    @Test
    fun `the window end is exclusive and the lunch break is off peak`() {
        assertEquals(PriceBranch.PEAK, UsagePriceResolver.ratesAt(deepSeek, utc(2, 3, 59))!!.branch)
        val noon = UsagePriceResolver.ratesAt(deepSeek, utc(2, 4, 0))!!
        assertEquals(PriceBranch.OFF_PEAK, noon.branch)
        assertEquals(1.0, noon.rates.inputPerMillion!!, 1e-9)
    }

    @Test
    fun `the second daily window is peak too`() {
        assertEquals(PriceBranch.PEAK, UsagePriceResolver.ratesAt(deepSeek, utc(2, 6, 30))!!.branch)
        assertEquals(PriceBranch.OFF_PEAK, UsagePriceResolver.ratesAt(deepSeek, utc(2, 10, 0))!!.branch)
    }

    @Test
    fun `the weekday filter survives the weekend and resumes on monday`() {
        assertEquals(PriceBranch.OFF_PEAK, UsagePriceResolver.ratesAt(deepSeek, utc(3, 1, 30))!!.branch)
        assertEquals(PriceBranch.PEAK, UsagePriceResolver.ratesAt(deepSeek, utc(5, 1, 30))!!.branch)
    }

    @Test
    fun `a wrapping window covers the hours around local midnight`() {
        val overnight = ModelPricing(
            inputPerMillion = 2.0,
            offPeak = OffPeakRates(inputPerMillion = 1.0),
            peakWindows = listOf(PeakWindow(22 * 60, 2 * 60, zoneOffsetMinutes = 0)),
        )
        assertEquals(PriceBranch.PEAK, UsagePriceResolver.ratesAt(overnight, utc(2, 23, 30))!!.branch)
        assertEquals(PriceBranch.PEAK, UsagePriceResolver.ratesAt(overnight, utc(2, 0, 30))!!.branch)
        assertEquals(PriceBranch.OFF_PEAK, UsagePriceResolver.ratesAt(overnight, utc(2, 3, 0))!!.branch)
    }

    @Test
    fun `a partial off peak row inherits the peak numbers it does not override`() {
        val partial = ModelPricing(
            inputPerMillion = 2.0,
            outputPerMillion = 8.0,
            offPeak = OffPeakRates(inputPerMillion = 1.0),
            peakWindows = listOf(PeakWindow(9 * 60, 12 * 60)),
        )
        val offPeak = UsagePriceResolver.ratesAt(partial, utc(2, 13, 0))!!
        assertEquals(1.0, offPeak.rates.inputPerMillion!!, 1e-9)
        assertEquals(8.0, offPeak.rates.outputPerMillion!!, 1e-9)
    }

    @Test
    fun `a row without off peak rates applies the same rate around the clock`() {
        val flat = ModelPricing(inputPerMillion = 2.0, outputPerMillion = 8.0)
        assertEquals(PriceBranch.UNIFORM, UsagePriceResolver.ratesAt(flat, utc(3, 3, 0))!!.branch)
        assertEquals(2.0, UsagePriceResolver.ratesAt(flat, utc(3, 3, 0))!!.rates.inputPerMillion!!, 1e-9)
    }

    @Test
    fun `nothing priced stays unknown rather than free`() {
        assertNull(UsagePriceResolver.ratesAt(null, utc(2, 1, 30)))
        assertNull(UsagePriceResolver.ratesAt(ModelPricing(), utc(2, 1, 30))?.rates?.takeIf { !it.isEmpty })
        assertNull(UsagePriceResolver.costMicros(TokenUsage(promptTokens = 500), null))
        assertNull(UsagePriceResolver.costMicros(TokenUsage(promptTokens = 500), PriceRates(null, null, null, null)))
        assertNull(UsagePriceResolver.priceVersionId(null))
    }

    @Test
    fun `per token metadata prices a call when there is no explicit row`() {
        val resolved = UsagePriceResolver.ratesAt(
            pricing = null,
            atEpochMs = utc(2, 1, 30),
            fallbackInputPerToken = 0.0000002,
            fallbackOutputPerToken = 0.0000008,
        )!!
        assertEquals(PriceBranch.METADATA, resolved.branch)
        assertEquals(0.2, resolved.rates.inputPerMillion!!, 1e-9)
        assertEquals(
            280L,
            UsagePriceResolver.costMicros(
                TokenUsage(promptTokens = 1000, completionTokens = 100, totalTokens = 1100),
                resolved.rates,
            ),
        )
    }

    @Test
    fun `cache reads are billed at the cache rate and kept out of the billable input`() {
        val rates = UsagePriceResolver.ratesAt(deepSeek, utc(2, 1, 30))!!.rates
        val micros = UsagePriceResolver.costMicros(
            TokenUsage(
                promptTokens = 1000,
                completionTokens = 100,
                totalTokens = 1100,
                cachedTokens = 800,
                cachedTokensReported = true,
            ),
            rates,
        )
        // 200 billable input at 2.0 + 800 cache reads at 0.04 + 100 output at 8.0, per million.
        assertEquals(1232L, micros)
    }

    @Test
    fun `cache writes are billed once and are not charged as input as well`() {
        val rates = PriceRates(
            inputPerMillion = 2.0,
            outputPerMillion = 8.0,
            cacheHitPerMillion = null,
            cacheWritePerMillion = 2.5,
        )
        val micros = UsagePriceResolver.costMicros(
            TokenUsage(promptTokens = 1000, cacheWriteTokens = 300),
            rates,
        )
        // 700 input at 2.0 + 300 cache writes at 2.5, nothing on output.
        assertEquals(2150L, micros)
    }

    @Test
    fun `an unset cache rate falls back to the input rate instead of to free`() {
        val rates = PriceRates(2.0, 8.0, null, null)
        val micros = UsagePriceResolver.costMicros(
            TokenUsage(promptTokens = 1000, cachedTokens = 400, cachedTokensReported = true),
            rates,
        )
        assertEquals(2000L, micros)
    }

    @Test
    fun `the version id tracks the rates that applied, not the edit history`() {
        val peak = UsagePriceResolver.ratesAt(deepSeek, utc(2, 1, 30))!!
        val offPeak = UsagePriceResolver.ratesAt(deepSeek, utc(2, 4, 0))!!
        val peakAgain = UsagePriceResolver.ratesAt(deepSeek, utc(2, 6, 30))!!

        assertEquals(UsagePriceResolver.priceVersionId(peak), UsagePriceResolver.priceVersionId(peakAgain))
        assertNotEquals(UsagePriceResolver.priceVersionId(peak), UsagePriceResolver.priceVersionId(offPeak))
    }
}
