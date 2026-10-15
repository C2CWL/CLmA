package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Verifies that the per-turn wall-clock budget is now read from [ToolRuntimeLimits] rather
 * than a deleted compile-time constant, and that the default value is 30 minutes.
 *
 * Full GenerationHandler instantiation requires Android Context + full Koin graph — beyond JVM
 * unit test scope. Instead, we test the invariants that GenerationHandler.kt relies on:
 *  - The runtime holder field exists and is readable/writable.
 *  - The default value is 30 min (raised from the 10 min that used to be the hardcoded const).
 *  - Writes to the holder are immediately visible (the deleted const had only one value;
 *    the holder can change dynamically).
 */
class GenerationHandlerTurnBudgetTest {

    private var prevBudget: Long = 0L

    @Before
    fun save() { prevBudget = ToolRuntimeLimits.turnBudgetMs }

    @After
    fun restore() { ToolRuntimeLimits.turnBudgetMs = prevBudget }

    @Test
    fun defaultBudget_is30Minutes() {
        // The original hardcoded const in GenerationHandler.kt was 10 min; the default is
        // now 30 min.
        assertEquals(30L * 60L * 1_000L, TermuxDefaults.DEFAULT_TURN_BUDGET_MS)
    }

    @Test
    fun toolRuntimeLimits_initialDefault_matchesTheDefaultBudget() {
        // On a fresh JVM process the holder initialises from TermuxDefaults.DEFAULT_TURN_BUDGET_MS.
        // We read prevBudget (captured before any test runs) to check what the holder started at.
        assertEquals(TermuxDefaults.DEFAULT_TURN_BUDGET_MS, prevBudget)
    }

    @Test
    fun turnBudget_canBeChangedAtRuntime() {
        val newBudget = 5L * 60_000L
        ToolRuntimeLimits.turnBudgetMs = newBudget
        assertEquals(newBudget, ToolRuntimeLimits.turnBudgetMs)
        assertNotEquals(prevBudget, ToolRuntimeLimits.turnBudgetMs)
    }

    @Test
    fun turnBudget_clampedValueFitsInHolder() {
        // Verify that a clamped value from TermuxDefaults survives a round-trip through
        // the holder without loss (no cast / truncation hazard).
        val clamped = TermuxDefaults.clampTurnBudgetMs(TermuxDefaults.MAX_TURN_BUDGET_MS + 1L) // over max → should snap
        ToolRuntimeLimits.turnBudgetMs = clamped
        assertEquals(clamped, ToolRuntimeLimits.turnBudgetMs)
        assertEquals(TermuxDefaults.MAX_TURN_BUDGET_MS, ToolRuntimeLimits.turnBudgetMs)
    }
}
