package com.clma.app.ui.pages.chat

import androidx.compose.material3.DrawerValue
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerCommandsTest {
    @Test fun rapidSameDirectionRequestsStartOneAnimation() = runTest {
        val starts = mutableListOf<DrawerValue>()
        val commands = DrawerCommands(this) { starts += it; awaitCancellation() }
        repeat(10) { commands.open() }
        runCurrent()
        repeat(10) { commands.open() }
        runCurrent()
        assertEquals(listOf(DrawerValue.Open), starts)
        commands.dispose()
    }

    @Test fun reverseStartsImmediatelyAndOldCleanupCannotClearItsOwnership() = runTest {
        val starts = mutableListOf<DrawerValue>()
        val commands = DrawerCommands(this) { target ->
            starts += target
            try { awaitCancellation() }
            finally { if (target == DrawerValue.Open) withContext(NonCancellable) { delay(30) } }
        }
        commands.open(); runCurrent()
        commands.close(); runCurrent()
        assertEquals(listOf(DrawerValue.Open, DrawerValue.Closed), starts)
        advanceTimeBy(31); runCurrent()
        commands.close(); runCurrent()
        assertEquals(2, starts.size)
        commands.dispose()
    }

    @Test fun latestTargetWinsOpenCloseOpenBeforeDispatch() = runTest {
        var final: DrawerValue? = null
        val starts = mutableListOf<DrawerValue>()
        val commands = DrawerCommands(this) { target -> starts += target; delay(100); final = target }
        commands.open(); commands.close(); commands.open()
        advanceUntilIdle()
        assertEquals(listOf(DrawerValue.Open), starts)
        assertEquals(DrawerValue.Open, final)
    }

    @Test fun completedCommandAllowsALaterIndependentRequest() = runTest {
        val starts = mutableListOf<DrawerValue>()
        val commands = DrawerCommands(this) { starts += it }
        commands.open(); runCurrent()
        commands.close(); runCurrent()
        commands.open(); runCurrent()
        assertEquals(listOf(DrawerValue.Open, DrawerValue.Closed, DrawerValue.Open), starts)
    }

    @Test fun gestureCancellationReleasesCommandSoNextIntentCanRun() = runTest {
        val gesture = CompletableDeferred<Unit>()
        var starts = 0
        val commands = DrawerCommands(this) {
            starts++
            gesture.await()
            throw CancellationException("gesture took Material's drag mutex")
        }
        commands.open(); runCurrent()
        gesture.complete(Unit); runCurrent()
        commands.close(); runCurrent()
        assertEquals(2, starts)
    }

    @Test fun disposalCancelsOnceAndLateCallbacksCannotRestart() = runTest {
        var starts = 0
        var stops = 0
        val commands = DrawerCommands(this) {
            starts++
            try { awaitCancellation() } finally { stops++ }
        }
        commands.open(); runCurrent()
        commands.dispose(); commands.dispose(); runCurrent()
        commands.open(); commands.close(); runCurrent()
        assertEquals(1, starts)
        assertEquals(1, stops)
    }

    @Test fun backIsConsumedWhileOpeningOrClosingButNotWhenFullyClosed() {
        assertTrue(drawerBackEnabled(DrawerValue.Closed, DrawerValue.Open, true))
        assertTrue(drawerBackEnabled(DrawerValue.Open, DrawerValue.Closed, true))
        assertTrue(drawerBackEnabled(DrawerValue.Closed, DrawerValue.Closed, true))
        assertFalse(drawerBackEnabled(DrawerValue.Closed, DrawerValue.Closed, false))
    }

    @Test fun openingWithImeUsesExistingFocusGateAndFontScaleDoesNotMoveAnchors() {
        assertTrue(shouldClearFocusOnDrawerOpen(DrawerValue.Open, true))
        assertFalse(shouldClearFocusOnDrawerOpen(DrawerValue.Open, false))
        assertFalse(shouldClearFocusOnDrawerOpen(DrawerValue.Closed, true))
        for (fontScale in listOf(0.8f, 1f, 1.8f)) {
            val density = drawerAnchorDensity(Density(2.72f, fontScale))
            assertEquals(2.72f, density.density, 0f)
            assertEquals(1f, density.fontScale, 0f)
        }
    }
}
