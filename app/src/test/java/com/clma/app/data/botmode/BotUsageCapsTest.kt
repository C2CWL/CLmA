package com.clma.app.data.botmode

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BotUsageCapsTest {
    private val now = Instant.parse("2026-11-28T23:00:00Z")
    private val dayStart = Instant.parse("2026-11-28T00:00:00Z")
    private val monthStart = Instant.parse("2026-11-01T00:00:00Z")

    @Test
    fun disabledAndUnattendedOnlyCapsDoNotQueryForInteractiveTurns() = runBlocking {
        assertNull(BotUsageCaps().reachedUntil(true, now) { error("No caps configured") })
        assertNull(BotUsageCaps(dailyTokens = 10).reachedUntil(false, now) { error("Interactive bypass") })
    }

    @Test
    fun blocksAtTheDailyLimitAndResetsAtUtcMidnight() = runBlocking {
        val caps = BotUsageCaps(dailyTokens = 100, stopsInteractive = true)
        val reset = caps.reachedUntil(false, now) {
            assertEquals(dayStart.toEpochMilli(), it)
            BotUsageTotal(100, 0.0)
        }
        assertEquals(Instant.parse("2026-11-29T00:00:00Z"), reset)
    }

    @Test
    fun reachedMonthlyCostCapKeepsTheBotBlockedPastTheDailyReset() = runBlocking {
        val caps = BotUsageCaps(dailyUsd = 1.0, monthlyUsd = 5.0)
        val reset = caps.reachedUntil(true, now) {
            assertEquals(monthStart.toEpochMilli(), it)
            BotUsageTotal(200, 5.0)
        }
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), reset)
    }
}
