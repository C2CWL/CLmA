package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {
    @Test
    fun `merge carries cost from the incoming chunk`() {
        val merged = (null as TokenUsage?).merge(
            TokenUsage(promptTokens = 10, completionTokens = 5, cost = 0.0012)
        )
        assertEquals(0.0012, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge keeps prior cost when the incoming chunk has none`() {
        // Streaming: an early chunk reported cost; a later token-only delta must not wipe it.
        val merged = TokenUsage(cost = 0.0034).merge(TokenUsage(completionTokens = 3))
        assertEquals(0.0034, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge prefers the newest cost`() {
        val merged = TokenUsage(cost = 0.001).merge(TokenUsage(cost = 0.002))
        assertEquals(0.002, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge leaves cost null when neither side reports it`() {
        val merged = TokenUsage(promptTokens = 1).merge(TokenUsage(completionTokens = 1))
        assertNull(merged.cost)
    }

    // --- P2-11a: provenance + provider-reported total ------------------------------------

    @Test
    fun `merge prefers the provider-reported total over a recomputed sum`() {
        // Some providers count tokens that prompt+completion does not cover (reasoning,
        // cache read/write). Recomputing the total diverges from the billed value.
        val merged = (null as TokenUsage?).merge(
            TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 130)
        )
        assertEquals(130, merged.totalTokens)
    }

    @Test
    fun `merge falls back to the sum when no total is reported`() {
        val merged = TokenUsage(promptTokens = 10, completionTokens = 5).merge(TokenUsage())
        assertEquals(15, merged.totalTokens)
    }

    @Test
    fun `merge keeps the newest reported total`() {
        val merged = TokenUsage(totalTokens = 100).merge(
            TokenUsage(completionTokens = 3, totalTokens = 103)
        )
        assertEquals(103, merged.totalTokens)
    }

    @Test
    fun `cache provenance survives a token-only delta`() {
        val merged = TokenUsage(
            cachedTokens = 7,
            cachedTokensReported = true,
            cacheMissTokens = 3,
            reasoningTokens = 9,
        ).merge(TokenUsage(completionTokens = 2))
        assertEquals(true, merged.cachedTokensReported)
        assertEquals(7, merged.cachedTokens)
        assertEquals(3, merged.cacheMissTokens)
        assertEquals(9, merged.reasoningTokens)
    }

    @Test
    fun `an unreported cache stays unreported instead of becoming a zero`() {
        val merged = (null as TokenUsage?).merge(TokenUsage(promptTokens = 10, completionTokens = 2))
        assertEquals(false, merged.cachedTokensReported)
        assertEquals(0, merged.cachedTokens) // legacy field: unchanged on purpose
        assertNull(merged.cacheMissTokens)
        assertNull(merged.reasoningTokens)
    }

    @Test
    fun `an incoming report upgrades the provenance flag`() {
        val merged = TokenUsage(cachedTokens = 0).merge(
            TokenUsage(cachedTokens = 5, cachedTokensReported = true, cacheMissTokens = 1)
        )
        assertEquals(true, merged.cachedTokensReported)
        assertEquals(5, merged.cachedTokens)
        assertEquals(1, merged.cacheMissTokens)
    }

    @Test
    fun `cache write tokens survive the merge`() {
        val kept = TokenUsage(cacheWriteTokens = 25, cachedTokensReported = true)
            .merge(TokenUsage(completionTokens = 1))
        assertEquals(25, kept.cacheWriteTokens)

        val upgraded = TokenUsage().merge(TokenUsage(cacheWriteTokens = 9))
        assertEquals(9, upgraded.cacheWriteTokens)

        assertNull(TokenUsage().merge(TokenUsage()).cacheWriteTokens)
    }
}
