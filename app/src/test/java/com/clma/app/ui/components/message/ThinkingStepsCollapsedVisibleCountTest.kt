package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [thinkingStepsCollapsedVisibleCount] — how many trailing steps a collapsed
 * ChainOfThought group keeps visible, driven by DisplaySetting.thinkingStepsVisibleCount.
 */
class ThinkingStepsCollapsedVisibleCountTest {

    @Test
    fun `default setting keeps the previous two visible steps`() {
        val default = DisplaySetting().thinkingStepsVisibleCount
        assertEquals(2, default)
        assertEquals(2, thinkingStepsCollapsedVisibleCount(default, isStreamingTail = false))
        assertEquals(2, thinkingStepsCollapsedVisibleCount(default, isStreamingTail = true))
    }

    @Test
    fun `zero fully collapses a finished group`() {
        assertEquals(0, thinkingStepsCollapsedVisibleCount(0, isStreamingTail = false))
    }

    @Test
    fun `the group still being generated keeps at least one step visible`() {
        assertEquals(1, thinkingStepsCollapsedVisibleCount(0, isStreamingTail = true))
        assertEquals(5, thinkingStepsCollapsedVisibleCount(5, isStreamingTail = true))
    }

    @Test
    fun `user chosen counts pass through`() {
        for (n in 0..THINKING_STEPS_VISIBLE_MAX) {
            assertEquals(n, thinkingStepsCollapsedVisibleCount(n, isStreamingTail = false))
        }
    }

    @Test
    fun `out of range values from a restored backup are clamped`() {
        assertEquals(0, thinkingStepsCollapsedVisibleCount(-3, isStreamingTail = false))
        assertEquals(
            THINKING_STEPS_VISIBLE_MAX,
            thinkingStepsCollapsedVisibleCount(99, isStreamingTail = false),
        )
    }

    @Test
    fun `all shows every step, finished or streaming`() {
        assertEquals(Int.MAX_VALUE, thinkingStepsCollapsedVisibleCount(THINKING_STEPS_VISIBLE_ALL, isStreamingTail = false))
        assertEquals(Int.MAX_VALUE, thinkingStepsCollapsedVisibleCount(THINKING_STEPS_VISIBLE_ALL, isStreamingTail = true))
    }

    @Test
    fun `the slider's top stop maps to all and back`() {
        val top = THINKING_STEPS_VISIBLE_MAX + 1
        assertEquals(THINKING_STEPS_VISIBLE_ALL, thinkingStepsVisibleFromSlider(top))
        assertEquals(top, thinkingStepsVisibleToSlider(THINKING_STEPS_VISIBLE_ALL))
    }

    @Test
    fun `numeric slider stops round-trip`() {
        for (n in 0..THINKING_STEPS_VISIBLE_MAX) {
            assertEquals(n, thinkingStepsVisibleFromSlider(n))
            assertEquals(n, thinkingStepsVisibleToSlider(n))
        }
    }
}
