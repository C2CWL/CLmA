package com.clma.app.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-turn tool budget.
 *
 * These are the cases the chat paths depend on: pins and activations are never dropped, the
 * budget caps only the guesses, and a later step of the same turn never loses a name an earlier
 * step had.
 */
class ToolRankFusionTest {

    @Test
    fun `a turn gets its pins, then its activations, then the guesses`() {
        val attached = ToolRankFusion.attachedForTurn(
            ranked = listOf("read_window_tree", "tap", "scroll"),
            pinned = listOf("memory_read", "read_window_tree"),
            activated = listOf("clipboard_tool"),
            budget = 4,
        )
        // `read_window_tree` is both pinned and retrieved: it keeps its pin position instead of
        // appearing twice, and it does not spend one of the four guesses.
        assertEquals(
            listOf("memory_read", "read_window_tree", "clipboard_tool", "tap", "scroll"),
            attached,
        )
    }

    @Test
    fun `the budget caps the guesses and nothing else`() {
        val attached = ToolRankFusion.attachedForTurn(
            ranked = listOf("a", "b", "c", "d"),
            pinned = listOf("p1", "p2", "p3"),
            activated = listOf("opened"),
            budget = 2,
        )
        // Four names are on the surface that no budget was consulted about — the user asked for
        // three of them and the model for the fourth. Only the two guesses were capped.
        assertEquals(listOf("p1", "p2", "p3", "opened", "a", "b"), attached)
    }

    @Test
    fun `a zero budget still honours pins and activations`() {
        // "Do not guess for me" is about the automatic half, not about stripping the tools the
        // user asked for by name or the model opened on purpose.
        assertEquals(
            listOf("pinned", "opened"),
            ToolRankFusion.attachedForTurn(
                ranked = listOf("a"),
                pinned = listOf("pinned"),
                activated = listOf("opened"),
                budget = 0,
            ),
        )
    }

    @Test
    fun `a turn with nothing to go on attaches nothing`() {
        assertTrue(ToolRankFusion.attachedForTurn(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `an activation made after the snapshot lands on the very next pass`() {
        // What `tool_open` buys. The turn's snapshot is taken before the model can open
        // anything, so the tool it opens has to be re-derived from the *current* activation set
        // on the next step — that is the whole point of re-reading this per step instead of
        // reusing the snapshot until the user speaks again.
        val ranked = listOf("a", "b", "c")
        assertEquals(
            listOf("a"),
            ToolRankFusion.attachedForTurn(ranked = ranked, pinned = emptyList(), activated = emptyList(), budget = 1),
        )
        // The model opened `c`. It bypasses the guess budget, so it arrives even though the
        // ranking never put it first.
        assertEquals(
            listOf("c", "a"),
            ToolRankFusion.attachedForTurn(ranked = ranked, pinned = emptyList(), activated = listOf("c"), budget = 1),
        )
    }

    @Test
    fun `growing activations can only grow the attached set`() {
        // The frozen guess plus a monotonically growing activation set: a later pass must never
        // lose a name an earlier pass had. If it could, re-deriving per step would be a way to
        // silently drop a tool mid-turn.
        val ranked = listOf("a", "b", "c", "d")
        // A real turn only ever grows this set: `activate` appends, and `retain` (which can drop
        // names) runs once, before the snapshot. So the chain below is what the provider actually
        // sees, step by step.
        val passes = listOf(
            emptyList<String>(),
            listOf("c"),
            listOf("c", "d"),
            listOf("c", "d", "z"),
        ).map { activated ->
            ToolRankFusion.attachedForTurn(
                ranked = ranked,
                pinned = listOf("p"),
                activated = activated,
                budget = 2,
            )
        }
        passes.zipWithNext { before, after ->
            assertTrue("$after lost a name from $before", after.containsAll(before))
        }
        // No name the ranking never guessed is needed to see the property, but the opened ones
        // have to arrive even when the budget is already spent by guesses.
        assertTrue(passes.last().containsAll(listOf("p", "z", "c", "d")))
    }
}