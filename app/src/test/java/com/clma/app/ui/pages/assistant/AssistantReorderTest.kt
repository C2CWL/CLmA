package me.rerere.rikkahub.ui.pages.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantReorderTest {
    @Test
    fun moveDownInsertsAtTheTargetSlot() {
        val items = mutableListOf("A", "B", "C", "D")
        AssistantReorder.move(items, 0, 2)
        assertEquals(listOf("B", "C", "A", "D"), items)
    }

    @Test
    fun moveUpInsertsAtTheTargetSlot() {
        val items = mutableListOf("A", "B", "C", "D")
        AssistantReorder.move(items, 3, 1)
        assertEquals(listOf("A", "D", "B", "C"), items)
    }

    @Test
    fun moveSameIndexIsNoOp() {
        val items = mutableListOf("A", "B")
        AssistantReorder.move(items, 1, 1)
        assertEquals(listOf("A", "B"), items)
    }

    @Test
    fun moveOutOfRangeIsNoOp() {
        val items = mutableListOf("A", "B")
        AssistantReorder.move(items, -1, 0)
        AssistantReorder.move(items, 0, 5)
        assertEquals(listOf("A", "B"), items)
    }

    @Test
    fun mergeAppliesDraggedOrder() {
        val merged = AssistantReorder.merge(listOf("a", "b", "c"), listOf("c", "a", "b")) { it }
        assertEquals(listOf("c", "a", "b"), merged)
    }

    @Test
    fun mergeKeepsItemsCreatedDuringTheGesture() {
        val merged = AssistantReorder.merge(listOf("a", "b", "new"), listOf("b", "a")) { it }
        assertEquals(listOf("b", "a", "new"), merged)
    }

    @Test
    fun mergeDoesNotResurrectDeletedItems() {
        val merged = AssistantReorder.merge(listOf("a", "c"), listOf("c", "b", "a")) { it }
        assertEquals(listOf("c", "a"), merged)
    }

    @Test
    fun mergeKeepsRelativeOrderOfUnrankedItems() {
        val merged = AssistantReorder.merge(listOf("a", "x", "b", "y"), listOf("b", "a")) { it }
        assertEquals(listOf("b", "a", "x", "y"), merged)
    }

    @Test
    fun mergeEmptyOrderLeavesCurrentUntouched() {
        val merged = AssistantReorder.merge(listOf("a", "b"), emptyList<String>()) { it }
        assertEquals(listOf("a", "b"), merged)
    }
}
