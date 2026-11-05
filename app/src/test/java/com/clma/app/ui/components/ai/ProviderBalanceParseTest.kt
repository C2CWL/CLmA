package com.clma.app.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBalanceParseTest {

    @Test
    fun `parses pipe joined grok style balance into labeled tiles`() {
        val segments = parseProviderBalanceSegments(
            "🔒 仅付费池 | 订阅:余78%(11-11 09:48) | 历史合计今日:1.24M",
        )

        assertEquals(4, segments.size)
        assertEquals(ProviderBalanceSegmentKind.PoolType, segments[0].kind)
        assertEquals("仅付费池", segments[0].value)

        assertEquals(ProviderBalanceSegmentKind.SubscriptionRemaining, segments[1].kind)
        assertEquals("78%", segments[1].value)

        assertEquals(ProviderBalanceSegmentKind.RefreshedAt, segments[2].kind)
        assertEquals("11-11 09:48", segments[2].value)

        assertEquals(ProviderBalanceSegmentKind.TodayTotal, segments[3].kind)
        assertEquals("1.24M", segments[3].value)
        assertEquals("历史合计今日", segments[3].rawLabel)
    }

    @Test
    fun `supports fullwidth colon and plain numeric balance`() {
        val segments = parseProviderBalanceSegments("余额：12.50")
        assertEquals(1, segments.size)
        assertEquals("余额", segments[0].rawLabel)
        assertEquals("12.50", segments[0].value)
    }

    @Test
    fun `falls back to single segment when there are no pipes`() {
        val segments = parseProviderBalanceSegments("\$6.54 USD")
        assertEquals(1, segments.size)
        assertEquals("\$6.54 USD", segments[0].value)
    }

    @Test
    fun `never drops information for unfamiliar shapes`() {
        val input = "alpha | beta:gamma | weird"
        val segments = parseProviderBalanceSegments(input)
        assertEquals(3, segments.size)
        assertEquals("alpha", segments[0].value)
        assertEquals("beta", segments[1].rawLabel)
        assertEquals("gamma", segments[1].value)
        assertEquals("weird", segments[2].value)
        val rejoined = segments.joinToString(" | ") {
            if (it.rawLabel != null) "${it.rawLabel}:${it.value}" else it.value
        }
        assertTrue(rejoined.contains("alpha"))
        assertTrue(rejoined.contains("gamma"))
        assertTrue(rejoined.contains("weird"))
    }

    @Test
    fun `compact chips keep pool subscription and today`() {
        val segments = parseProviderBalanceSegments(
            "🔒 仅付费池 | 订阅:余78%(11-11 09:48) | 历史合计今日:1.24M",
        )
        val chips = compactProviderBalanceChips(segments)
        assertEquals(2, chips.size)
        assertEquals("付费池 78%", chips[0].value)
        assertEquals(ProviderBalanceSegmentKind.SubscriptionRemaining, chips[0].kind)
        assertEquals("1.24M", chips[1].value)
        assertEquals(ProviderBalanceSegmentKind.TodayTotal, chips[1].kind)
    }

    @Test
    fun `empty input yields no segments`() {
        assertTrue(parseProviderBalanceSegments("").isEmpty())
        assertTrue(parseProviderBalanceSegments("   ").isEmpty())
    }
}
