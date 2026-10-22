package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the `read_window_tree(diff = true)` diff. The value of the feature is that a post-action
 * read reports a handful of nodes instead of the whole screen, so what matters is that "new" and
 * "gone" are computed against the signatures and that duplicates cannot inflate the result.
 */
class TreeDeltaTest {

    @Test
    fun `only genuinely new nodes are reported as added`() {
        val current = listOf("a", "b", "c")
        val previous = setOf("a", "c")

        assertEquals(listOf(1), TreeDelta.addedIndices(current, previous))
    }

    @Test
    fun `a first read with no history reports nothing as added`() {
        // previous is the empty set only when there is no snapshot at all; the caller falls back
        // to a full tree instead of a diff, so this is just the arithmetic contract.
        assertEquals(emptyList<Int>(), TreeDelta.addedIndices(emptyList(), emptySet()))
        assertEquals(listOf(0, 1), TreeDelta.addedIndices(listOf("a", "b"), emptySet()))
    }

    @Test
    fun `vanished nodes are reported once, in first-seen order`() {
        val current = setOf("a")
        val previous = listOf("a", "b", "b", "c")

        assertEquals(listOf("b", "c"), TreeDelta.removedSignatures(current, previous))
    }

    @Test
    fun `an unchanged screen has no delta at all`() {
        val signatures = listOf("a", "b")
        assertEquals(emptyList<Int>(), TreeDelta.addedIndices(signatures, signatures.toSet()))
        assertEquals(emptyList<String>(), TreeDelta.removedSignatures(signatures.toSet(), signatures))
    }
}
