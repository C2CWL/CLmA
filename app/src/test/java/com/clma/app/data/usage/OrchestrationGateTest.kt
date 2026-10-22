package me.rerere.rikkahub.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestrationGateTest {

    private fun refuse(used: Long, budget: Long?): OrchestrationGate.Decision.Refuse =
        OrchestrationGate.decide(used, budget) as OrchestrationGate.Decision.Refuse

    @Test fun `no budget always allows`() {
        assertEquals(OrchestrationGate.Decision.Allow, OrchestrationGate.decide(0L, null))
        assertEquals(OrchestrationGate.Decision.Allow, OrchestrationGate.decide(9_999_999L, null))
    }

    @Test fun `usage below the ceiling allows`() {
        assertEquals(OrchestrationGate.Decision.Allow, OrchestrationGate.decide(9_999L, 10_000L))
    }

    @Test fun `zero usage under a positive budget allows`() {
        assertEquals(OrchestrationGate.Decision.Allow, OrchestrationGate.decide(0L, 1L))
    }

    @Test fun `reaching the ceiling already refuses`() {
        val decision = refuse(10_000L, 10_000L)
        assertEquals(10_000L, decision.usedTokens)
        assertEquals(10_000L, decision.budgetTokens)
        assertEquals(0L, decision.overByTokens)
    }

    @Test fun `past the ceiling refuses and reports the excess`() {
        val decision = refuse(12_345L, 10_000L)
        assertEquals(12_345L, decision.usedTokens)
        assertEquals(10_000L, decision.budgetTokens)
        assertEquals(2_345L, decision.overByTokens)
    }

    @Test fun `a zero budget refuses an untouched orchestration`() {
        assertTrue(OrchestrationGate.decide(0L, 0L) is OrchestrationGate.Decision.Refuse)
    }

    @Test fun `detail names both numbers, the excess and both ways out`() {
        val detail = OrchestrationGate.refusalDetail(refuse(12_345L, 10_000L))
        assertTrue(detail, detail.contains("12345"))
        assertTrue(detail, detail.contains("10000"))
        assertTrue(detail, detail.contains("2345"))
        assertTrue(detail, detail.contains("inline"))
    }

    @Test fun `error code is stable`() {
        assertEquals("budget_exceeded", OrchestrationGate.ERROR_CODE)
    }

    /** The gate must never disagree with the single rule it wraps. */
    @Test fun `decision agrees with OrchestrationBudget on every combination`() {
        for (used in listOf(0L, 5L, 10L, 11L, 100L)) {
            for (budget in listOf<Long?>(null, 0L, 10L, 100L)) {
                val refused = OrchestrationGate.decide(used, budget) is OrchestrationGate.Decision.Refuse
                assertEquals(
                    "used=$used budget=$budget",
                    OrchestrationBudget.exceeded(used, budget),
                    refused,
                )
            }
        }
    }
}
