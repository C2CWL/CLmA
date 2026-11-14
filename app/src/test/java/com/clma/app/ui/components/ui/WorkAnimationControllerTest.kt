package com.clma.app.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkAnimationControllerTest {
    @Test fun idleOrHiddenIndicatorsStartNoAnimator() {
        var starts = 0
        val clock = WorkAnimationController({ starts++ }, {})
        clock.update(false, true)
        clock.update(true, false)
        clock.update(false, false)
        assertEquals(0, starts)
        assertFalse(clock.running)
    }

    @Test fun ordinaryUpdatesDoNotRestartAnActiveAnimation() {
        var starts = 0
        var stops = 0
        val clock = WorkAnimationController({ starts++ }, { stops++ })
        repeat(20) { clock.update(true, true) }
        assertEquals(1, starts)
        assertEquals(0, stops)
        assertTrue(clock.running)
    }

    @Test fun pauseDetachAndResumeStopAndRestartExactlyOnce() {
        val events = mutableListOf<String>()
        val clock = WorkAnimationController({ events.add("start") }, { events.add("stop") })
        clock.update(true, true)
        clock.update(false, true) // Lifecycle paused.
        clock.update(false, false) // Then detached.
        clock.update(true, false) // Resume while still detached cannot start.
        clock.update(true, true)
        clock.update(true, false) // Scrolled/disposed from its native view hierarchy.
        assertEquals(listOf("start", "stop", "start", "stop"), events)
    }

    @Test fun disposeIsTerminalAndCancelsAnActiveClockOnce() {
        var starts = 0
        var stops = 0
        val clock = WorkAnimationController({ starts++ }, { stops++ })
        clock.update(true, true)
        clock.release()
        clock.release()
        clock.update(true, true) // A late view/lifecycle callback cannot revive it.
        assertEquals(1, starts)
        assertEquals(1, stops)
        assertFalse(clock.running)
    }

    @Test fun dotPulseRestoresOriginalTweenEasingAndBrightness() {
        assertEquals(0.3f, dotLoadingAlpha(0f), 0.00001f)
        assertEquals(1f, dotLoadingAlpha(0.5f), 0.00001f)
        assertEquals(0.3f, dotLoadingAlpha(1f), 0.00001f)
        // Original FastOutSlowIn tween is brighter here than the downgraded linear triangle (.65).
        assertTrue(dotLoadingAlpha(0.25f) > 0.8f)
        assertEquals(dotLoadingAlpha(0.25f), dotLoadingAlpha(0.75f), 0.00001f)
    }
}
