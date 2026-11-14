package com.clma.app.ui.components.ai

import androidx.compose.ui.text.TextRange
import com.clma.app.data.datastore.ChatLayout
import com.clma.app.data.datastore.resolveChatLayout
import com.clma.app.ui.hooks.ChatInputState
import org.junit.Assert.*
import org.junit.Test

class InputBarGeometryTest {
    private fun endpoint(open: Boolean, width: Int = 320, left: Int = 40, right: Int = 80, textHeight: Int = 56) =
        inputBarEndpoint(open, width, left, right, 32, 36, textHeight, 4)

    @Test fun expandedLabelsDoNotOverwriteTheActualCollapsedStart() {
        val state = InputBarGeometry()
        val closed = endpoint(false)
        state.update(false, closed)
        assertEquals(closed, state.frame(0f))
        state.update(true, endpoint(true, left = 220, right = 160))
        assertEquals(closed, state.frame(0f))
        assertEquals(192, state.frame(0f).centerWidth)
    }

    @Test fun openedEndpointUsesFullWidthAndSeparateToolsRow() {
        val state = InputBarGeometry()
        state.update(false, endpoint(false))
        val opened = endpoint(true, left = 180, right = 120)
        state.update(true, opened)
        assertEquals(opened, state.frame(1f))
        assertEquals(320, opened.centerWidth)
        assertEquals(96, opened.height)
        assertEquals(60, opened.rightY)
    }

    @Test fun reverseAtHalfwayDoesNotJumpGeometryOrNeedAnotherField() {
        val state = InputBarGeometry()
        val closed = endpoint(false)
        state.update(false, closed)
        state.update(true, endpoint(true, left = 160, right = 120))
        val before = state.frame(0.45f)
        state.update(false, closed)
        assertEquals(before, state.frame(0.45f))
        assertEquals(closed, state.frame(0f))
    }

    @Test fun longDraftEndpointPredictionDoesNotUseIntermediateWrappingHeight() {
        val state = InputBarGeometry()
        state.update(false, endpoint(false, textHeight = 136))
        val measuredWidths = mutableSetOf<Int>()
        val heights = (0..22).map { tick ->
            val width = inputBarFieldWidth(true, 320, 200, 112, 4)
            measuredWidths += width
            // Lookahead endpoint prediction is independent of the real field's intermediate wrapping.
            state.update(true, endpoint(true, left = 200, right = 112, textHeight = if (width >= 300) 88 else 136))
            state.frame(tick / 22f).height
        }
        assertEquals(setOf(320), measuredWidths)
        assertEquals(136, heights.first())
        assertEquals(128, heights.last())
        assertTrue(heights.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test fun longLabelsOnNarrowScreensCannotConsumeTheEditableViewport() {
        for (width in listOf(8, 120, 180, 240, 320)) {
            val gap = minOf(4, width / 2)
            val budget = inputBarActionBudget(false, width, gap, 64)
            val right = minOf(200, budget)
            val left = minOf(400, (budget - right).coerceAtLeast(0))
            val field = inputBarFieldWidth(false, width, left, right, gap)
            assertTrue(field >= minOf(64, (width - gap * 2).coerceAtLeast(0)))
            assertEquals(width, left + right + gap * 2 + field)
        }
    }

    @Test fun allThreeSelectableLayoutsUseTheSameContinuousTransition() {
        for (layout in listOf(ChatLayout.CLEAN, ChatLayout.MODERN, ChatLayout.GEMINI)) {
            assertEquals(layout, resolveChatLayout(layout, false))
            assertTrue(chatInputExpanded(true, false, false))
            val state = InputBarGeometry()
            val closed = endpoint(false, left = if (layout == ChatLayout.MODERN) 58 else 44)
            state.update(false, closed)
            state.update(true, endpoint(true, left = 190, right = 120))
            assertEquals(closed, state.frame(0f))
            assertEquals(320, state.frame(1f).centerWidth)
        }
        assertEquals(ChatLayout.GEMINI, resolveChatLayout(ChatLayout.ORIGINAL, false))
    }

    @Test fun initiallyExpandedDraftAndConstraintResizeHaveValidEndpoints() {
        val state = InputBarGeometry()
        state.update(true, endpoint(true, textHeight = 120))
        assertEquals(160, state.frame(1f).height)
        state.update(false, endpoint(false, width = 200, left = 32, right = 80))
        for (p in listOf(0f, 0.5f, 1f)) {
            val f = state.frame(p)
            assertEquals(200, f.width)
            assertTrue(f.centerWidth in 0..200)
        }
    }

    @Test fun emptyAndLongDraftSelectionAndStateIdentitySurviveRetargets() {
        for (draft in listOf("", "long draft ".repeat(40))) {
            val input = ChatInputState()
            input.setMessageText(draft)
            val selected = TextRange(0, minOf(8, draft.length))
            input.textContent.edit { selection = selected }
            val original = input.textContent
            val state = InputBarGeometry()
            state.update(false, endpoint(false))
            for (open in listOf(true, false, true, false)) {
                state.update(open, endpoint(open))
                state.frame(0.5f)
            }
            assertSame(original, input.textContent)
            assertEquals(draft, input.textContent.text.toString())
            assertEquals(selected, input.textContent.selection)
        }
    }
}
