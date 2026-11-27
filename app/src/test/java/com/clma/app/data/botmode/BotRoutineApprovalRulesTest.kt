package com.clma.app.data.botmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRoutineApprovalRulesTest {

    @Test
    fun `remaining budget subtracts time worked and treats negative work as zero`() {
        assertEquals(780_000L, BotRoutineApprovalRules.remainingBudgetMs(120_000L))
        assertEquals(900_000L, BotRoutineApprovalRules.remainingBudgetMs(-1L))
        assertEquals(100_000L, BotRoutineApprovalRules.remainingBudgetMs(20_000L, limitMs = 120_000L))
    }

    @Test
    fun `remaining budget is capped at what is left and never negative`() {
        assertEquals(1L, BotRoutineApprovalRules.remainingBudgetMs(899_999L))
        assertEquals(0L, BotRoutineApprovalRules.remainingBudgetMs(900_000L))
        assertEquals(0L, BotRoutineApprovalRules.remainingBudgetMs(900_001L))
    }

    @Test
    fun `parked run expires at its deadline`() {
        val run = ParkedRoutineRun(
            conversationId = "conversation",
            assistantId = "assistant",
            jobId = "job",
            jobName = "Morning briefing",
            runId = "run",
            scheduledAtMs = 1_000L,
            startedAtMs = 2_000L,
            workedMs = 500L,
            parkedAtMs = 2_500L,
            expiresAtMs = 5_000L,
        )

        assertFalse(BotRoutineApprovalRules.isExpired(run, nowMs = 4_999L))
        assertTrue(BotRoutineApprovalRules.isExpired(run, nowMs = 5_000L))
        assertTrue(BotRoutineApprovalRules.isExpired(run, nowMs = 5_001L))
    }

    @Test
    fun `expiry adds the default or supplied ttl to the parked time`() {
        assertEquals(86_402_500L, BotRoutineApprovalRules.expiresAt(2_500L))
        assertEquals(5_000L, BotRoutineApprovalRules.expiresAt(2_500L, ttlMs = 2_500L))
    }
}
