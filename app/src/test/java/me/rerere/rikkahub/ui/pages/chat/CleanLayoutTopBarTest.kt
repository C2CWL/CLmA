package me.rerere.rikkahub.ui.pages.chat

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
    fun `ledger tokens win, fallback only when the ledger is empty`() {
        var fallbackCalls = 0
        assertEquals(500L, usedTokensOrFallback(500L) { fallbackCalls++; 7L })
        assertEquals(0, fallbackCalls)
        assertEquals(7L, usedTokensOrFallback(0L) { fallbackCalls++; 7L })
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun `clean layout raises the top fade on a light photo`() {
        val regular = titleBarFade(darkTheme = false, photoShown = true)
        val clean = titleBarFade(darkTheme = false, photoShown = true, cleanLayout = true)
        assertTrue(clean.topAlpha > regular.topAlpha)
        assertTrue(clean.midAlpha > regular.midAlpha)
        assertEquals(0f, clean.bottomAlpha, 0f)
    }

    @Test
    fun `clean layout leaves every other case unchanged`() {
        for (dark in listOf(true, false)) for (photo in listOf(true, false)) {
            if (!dark && photo) continue
            assertEquals(
                titleBarFade(darkTheme = dark, photoShown = photo),
                titleBarFade(darkTheme = dark, photoShown = photo, cleanLayout = true),
            )
        }
    }
}
