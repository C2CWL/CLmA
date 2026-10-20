package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.ui.components.ui.cleanPanelAlpha
import org.junit.Assert.assertEquals
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
}
