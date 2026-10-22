package me.rerere.rikkahub.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsagePriceTableTest {

    private fun entry(
        providerName: String = "DeepSeek",
        modelId: String = "deepseek-chat",
        input: Double? = 2.0,
        output: Double? = 8.0,
        windows: List<PriceWindowSpec> = emptyList(),
    ) = PriceEntrySpec(
        providerName = providerName,
        modelId = modelId,
        inputPerMillion = input,
        outputPerMillion = output,
        peakWindows = windows,
    )

    @Test
    fun `a well formed table is accepted with trimmed names`() {
        val result = UsagePriceTable.validate(
            PriceTableSpec(version = "v1", entries = listOf(entry(providerName = " DeepSeek ")))
        )
        assertTrue(result.rejected.isEmpty())
        assertEquals("DeepSeek", result.accepted.single().providerName)
        assertEquals(true, result.isClean)
    }

    @Test
    fun `an entry with no rate at all is rejected - unpaid is not free`() {
        val result = UsagePriceTable.validate(
            PriceTableSpec(entries = listOf(entry(input = null, output = null)))
        )
        assertEquals("no rate given", result.rejected.single().reason)
    }

    @Test
    fun `blank names, negative and non-finite rates are each named in the rejection`() {
        val result = UsagePriceTable.validate(
            PriceTableSpec(
                entries = listOf(
                    entry(providerName = " "),
                    entry(modelId = ""),
                    entry(modelId = "negative", input = -1.0),
                    entry(modelId = "nan", output = Double.NaN),
                )
            )
        )
        val reasons = result.rejected.map { it.reason }
        assertEquals(
            listOf("blank provider name", "blank model id", "inputPerMillion is negative", "outputPerMillion is not a finite number"),
            reasons,
        )
    }

    @Test
    fun `the same provider and model twice is rejected rather than silently last-wins`() {
        val result = UsagePriceTable.validate(PriceTableSpec(entries = listOf(entry(), entry(input = 9.0))))
        assertEquals(1, result.accepted.size)
        assertEquals("duplicate entry for DeepSeek / deepseek-chat", result.rejected.single().reason)
    }

    @Test
    fun `peak windows are checked against a real clock`() {
        val bad = listOf(
            PriceWindowSpec(startMinute = 1440, endMinute = 60, zoneOffsetMinutes = 480),
            PriceWindowSpec(startMinute = 60, endMinute = 60, zoneOffsetMinutes = 480),
            PriceWindowSpec(startMinute = 60, endMinute = 120, zoneOffsetMinutes = 999),
            PriceWindowSpec(startMinute = 60, endMinute = 120, zoneOffsetMinutes = 480, daysOfWeek = listOf(0)),
        )
        val reasons = bad.map { w ->
            UsagePriceTable.validate(PriceTableSpec(entries = listOf(entry(windows = listOf(w))))).rejected.single().reason
        }
        assertEquals(
            listOf(
                "window[0] startMinute must be 0..1439",
                "window[0] start and end are the same minute",
                "window[0] zoneOffsetMinutes must be -720..840",
                "window[0] daysOfWeek is ISO 1..7 (Mon..Sun)",
            ),
            reasons,
        )
    }

    @Test
    fun `a cross midnight window is legal and days are deduplicated`() {
        val result = UsagePriceTable.validate(
            PriceTableSpec(entries = listOf(entry(windows = listOf(PriceWindowSpec(1320, 360, 480, listOf(5, 5, 1))))))
        )
        assertTrue(result.isClean)
        assertEquals(listOf(1, 5), result.accepted.single().peakWindows.single().daysOfWeek)
    }

    @Test
    fun `the table size cap rejects the excess instead of truncating in silence`() {
        val many = (0 until UsagePriceTable.MAX_ENTRIES + 2).map { entry(modelId = "m$it") }
        val result = UsagePriceTable.validate(PriceTableSpec(entries = many))
        assertEquals(UsagePriceTable.MAX_ENTRIES, result.accepted.size)
        assertEquals("table exceeds ${UsagePriceTable.MAX_ENTRIES} entries", result.rejected.first().reason)
    }

    @Test
    fun `a rate-less entry maps to no pricing, an off-peak only entry keeps its off-peak rates`() {
        assertNull(UsagePriceTable.toModelPricing(PriceEntrySpec(providerName = "p", modelId = "m")))

        val offPeakOnly = UsagePriceTable.toModelPricing(
            PriceEntrySpec(providerName = "p", modelId = "m", offPeakInputPerMillion = 1.0)
        )!!
        assertNull(offPeakOnly.inputPerMillion)
        assertEquals(1.0, offPeakOnly.offPeak!!.inputPerMillion!!, 0.0)
    }

    @Test
    fun `windows survive the trip into ModelPricing`() {
        val pricing = UsagePriceTable.toModelPricing(
            entry(windows = listOf(PriceWindowSpec(540, 720, 480, listOf(1, 2))))
        )!!
        val window = pricing.peakWindows.single()
        assertEquals(540, window.startMinute)
        assertEquals(720, window.endMinute)
        assertEquals(480, window.zoneOffsetMinutes)
        assertEquals(setOf(1, 2), window.daysOfWeek)
    }

    @Test
    fun `fromModelPricing round trips back to the same canonical text`() {
        val original = entry(windows = listOf(PriceWindowSpec(840, 1080, 480, listOf(3, 1))))
        val stored = UsagePriceTable.toModelPricing(original)!!
        val back = UsagePriceTable.fromModelPricing("DeepSeek", "deepseek-chat", stored)
        val normalized = UsagePriceTable.validate(PriceTableSpec(entries = listOf(original))).accepted.single()
        assertEquals(UsagePriceTable.canonical(listOf(normalized)), UsagePriceTable.canonical(listOf(back)))
    }

    @Test
    fun `the table version ignores entry order but notices a changed rate`() {
        val a = entry(modelId = "a")
        val b = entry(modelId = "b", input = 3.0)
        val forward = UsagePriceTable.tableVersionId(listOf(a, b))
        val reversed = UsagePriceTable.tableVersionId(listOf(b, a))
        assertEquals(forward, reversed)
        assertEquals(12, forward.length)
        assertTrue(forward.all { it in "0123456789abcdef" })

        val repriced = UsagePriceTable.tableVersionId(listOf(a, entry(modelId = "b", input = 3.5)))
        assertTrue(forward != repriced)
    }
}
