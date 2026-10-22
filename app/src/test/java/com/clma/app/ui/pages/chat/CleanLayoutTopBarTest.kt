package com.clma.app.ui.pages.chat

import com.clma.app.ui.components.ui.CleanGlassFillFactor
import com.clma.app.ui.components.ui.cleanGlassFillAlpha
import com.clma.app.ui.components.ui.cleanPanelAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanLayoutTopBarTest {

    @Test
    fun `subtitle keeps assistant model and token count`() {
        assertEquals("Rikka / GPT / 1234 tokens", topBarSubtitle("Rikka", "GPT", 1234L))
        assertEquals("Rikka / GPT / 1 token", topBarSubtitle("Rikka", "GPT", 1L))
        assertEquals("Rikka / GPT / 0 tokens", topBarSubtitle("Rikka", "GPT", 0L))
    }

    @Test
    fun `regular subtitle token suffix stays a complete trailing piece`() {
        assertEquals("Rikka / GPT", topBarSubtitleName("Rikka", "GPT"))
        assertEquals(" / 0 tokens", topBarTokenSuffix(0L))
        assertEquals(" / 1 token", topBarTokenSuffix(1L))
        assertEquals(" / 1234 tokens", topBarTokenSuffix(1234L))
        assertEquals(
            topBarSubtitleName("Rikka", "GPT") + topBarTokenSuffix(1234L),
            topBarSubtitle("Rikka", "GPT", 1234L),
        )
        // The count is a suffix of the full line, so the bar can ellipsize the name and still
        // show this piece whole. "0 tokens" must not collapse to "0 t".
        val suffix = topBarTokenSuffix(0L)
        assertTrue(topBarSubtitle("Long assistant", "gemini-3.8-flash-high", 0L).endsWith(suffix))
        assertTrue(suffix.endsWith("tokens"))
    }

    @Test
    fun `ledger tokens win, fallback only when the ledger is empty`() {
        var fallbackCalls = 0
        assertEquals(500L, usedTokensOrFallback(500L) { fallbackCalls++; 7L })
        assertEquals(0, fallbackCalls)
        assertEquals(7L, usedTokensOrFallback(0L) { fallbackCalls++; 7L })
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun `clean layout panels clamp their strength`() {
        // 0 = fully clear, 1 = the material's default strength; stored values outside that range
        // (older settings, bad imports) must not produce an out-of-gamut alpha.
        assertEquals(0f, cleanPanelAlpha(0f), 0f)
        assertEquals(1f, cleanPanelAlpha(1f), 0f)
        assertEquals(0.4f, cleanPanelAlpha(0.4f), 0f)
        assertEquals(0f, cleanPanelAlpha(-0.3f), 0f)
        assertEquals(1f, cleanPanelAlpha(2f), 0f)
    }

    @Test
    fun `glass fill scales with strength and stays below solid`() {
        assertEquals(0.85f, CleanGlassFillFactor, 0f)
        assertEquals(0f, cleanGlassFillAlpha(0f), 0f)
        assertEquals(0.34f, cleanGlassFillAlpha(0.4f), 1e-6f)
        assertEquals(0.85f, cleanGlassFillAlpha(1f), 0f)
        // Out-of-range stored values clamp before scaling.
        assertEquals(0f, cleanGlassFillAlpha(-1f), 0f)
        assertEquals(0.85f, cleanGlassFillAlpha(3f), 0f)
    }

    @Test
    fun `two-line title floor is both lines plus padding`() {
        // titleMedium 24sp + labelSmall 16sp at font scale 1 + 2dp top and bottom.
        assertEquals(44f, cleanTopBarTwoLineFloorDp(24f, 16f), 0f)
        // A larger font raises the floor with it (lines already converted to dp).
        assertEquals(52f, cleanTopBarTwoLineFloorDp(28.8f, 19.2f), 1e-4f)
    }

    @Test
    fun `subtitle hides only below the two-line floor`() {
        assertTrue(cleanTopBarShowsSubtitle(barHeightDp = 48f, titleLineDp = 24f, subtitleLineDp = 16f))
        assertTrue(cleanTopBarShowsSubtitle(barHeightDp = 44f, titleLineDp = 24f, subtitleLineDp = 16f))
        assertFalse(cleanTopBarShowsSubtitle(barHeightDp = 43f, titleLineDp = 24f, subtitleLineDp = 16f))
        assertFalse(cleanTopBarShowsSubtitle(barHeightDp = 40f, titleLineDp = 24f, subtitleLineDp = 16f))
        // At 120% font the default 48dp no longer fits two lines (28.8 + 19.2 + 4 = 52).
        assertFalse(cleanTopBarShowsSubtitle(barHeightDp = 48f, titleLineDp = 28.8f, subtitleLineDp = 19.2f))
        assertTrue(cleanTopBarShowsSubtitle(barHeightDp = 56f, titleLineDp = 28.8f, subtitleLineDp = 19.2f))
    }
}
