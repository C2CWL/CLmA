package me.rerere.rikkahub.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-07 — the budget precedence and the comparison P2-13 will enforce. No Room, no Android:
 * [OrchestrationBudget] is pure by design so that this is the whole test surface.
 */
class OrchestrationBudgetTest {

    // ---- effectiveBudget: expert overrides assistant, null = unlimited --------------------

    @Test
    fun `both null stays unlimited`() {
        assertNull(OrchestrationBudget.effectiveBudget(null, null))
    }

    @Test
    fun `expert null inherits the assistant budget`() {
        assertEquals(1_000L, OrchestrationBudget.effectiveBudget(1_000L, null))
    }

    @Test
    fun `assistant null with an expert budget uses the expert`() {
        assertEquals(500L, OrchestrationBudget.effectiveBudget(null, 500L))
    }

    @Test
    fun `expert budget overrides the assistant one`() {
        assertEquals(200L, OrchestrationBudget.effectiveBudget(1_000L, 200L))
    }

    @Test
    fun `an expert may raise the assistant budget too`() {
        // Precedence is "expert wins", not "the smaller wins": raising a ceiling is legitimate.
        assertEquals(5_000L, OrchestrationBudget.effectiveBudget(1_000L, 5_000L))
    }

    // ---- exceeded -------------------------------------------------------------------------

    @Test
    fun `no budget is never exceeded`() {
        assertFalse(OrchestrationBudget.exceeded(usedTokens = Long.MAX_VALUE, budget = null))
    }

    @Test
    fun `usage below the budget is not exceeded`() {
        assertFalse(OrchestrationBudget.exceeded(usedTokens = 999L, budget = 1_000L))
    }

    @Test
    fun `usage exactly at the budget is exceeded`() {
        assertTrue(OrchestrationBudget.exceeded(usedTokens = 1_000L, budget = 1_000L))
    }

    @Test
    fun `usage over the budget is exceeded`() {
        assertTrue(OrchestrationBudget.exceeded(usedTokens = 1_001L, budget = 1_000L))
    }

    @Test
    fun `a zero budget is exceeded by any usage`() {
        assertTrue(OrchestrationBudget.exceeded(usedTokens = 1L, budget = 0L))
    }

    @Test
    fun `no usage against a zero budget is already exceeded`() {
        // `>=` puts the boundary here: a budget of 0 allows nothing at all.
        assertTrue(OrchestrationBudget.exceeded(usedTokens = 0L, budget = 0L))
    }

    // ---- remaining ------------------------------------------------------------------------

    @Test
    fun `remaining is null without a budget`() {
        assertNull(OrchestrationBudget.remaining(usedTokens = 10L, budget = null))
    }

    @Test
    fun `remaining is the difference under the budget`() {
        assertEquals(400L, OrchestrationBudget.remaining(usedTokens = 600L, budget = 1_000L))
    }

    @Test
    fun `remaining is zero exactly at the budget`() {
        assertEquals(0L, OrchestrationBudget.remaining(usedTokens = 1_000L, budget = 1_000L))
    }

    @Test
    fun `remaining is clamped at zero over the budget`() {
        assertEquals(0L, OrchestrationBudget.remaining(usedTokens = 1_200L, budget = 1_000L))
    }
}
