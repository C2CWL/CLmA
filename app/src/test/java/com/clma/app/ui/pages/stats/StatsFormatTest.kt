package me.rerere.rikkahub.ui.pages.stats

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class StatsFormatTest {

    private lateinit var defaultLocale: Locale

    @Before
    fun pinCommaDecimalLocale() {
        // A comma-decimal default locale must not leak into the output: the unit suffixes are
        // English, so the decimal separator is pinned to "." with them.
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    // ---- tokens --------------------------------------------------------------------------

    @Test
    fun `small token counts stay plain integers`() {
        assertEquals("0", formatCompactTokens(0))
        assertEquals("999", formatCompactTokens(999))
    }

    @Test
    fun `token counts scale with one decimal in K`() {
        assertEquals("1.0K", formatCompactTokens(1_000))
        assertEquals("12.3K", formatCompactTokens(12_345))
        assertEquals("999.9K", formatCompactTokens(999_949))
    }

    @Test
    fun `a K value that rounds to 1000 is promoted to M`() {
        assertEquals("1.00M", formatCompactTokens(999_950))
        assertEquals("1.00M", formatCompactTokens(999_999))
        assertEquals("1.50M", formatCompactTokens(1_500_000))
    }

    @Test
    fun `an M value that rounds to 1000 is promoted to B`() {
        assertEquals("999.99M", formatCompactTokens(999_994_999))
        assertEquals("1.00B", formatCompactTokens(999_995_000))
        assertEquals("2.50B", formatCompactTokens(2_500_000_000))
    }

    // ---- counts --------------------------------------------------------------------------

    @Test
    fun `plain counts keep one decimal in every unit`() {
        assertEquals("42", formatCompactCount(42))
        assertEquals("1.0K", formatCompactCount(1_000))
        assertEquals("12.3K", formatCompactCount(12_345))
        assertEquals("1.0M", formatCompactCount(999_950))
        assertEquals("3.2M", formatCompactCount(3_210_987))
    }

    // ---- rate ----------------------------------------------------------------------------

    @Test
    fun `the rate keeps one decimal and a dot separator`() {
        assertEquals("12.3", formatTokenRate(12.34))
        assertEquals("0.0", formatTokenRate(0.04))
    }
}
