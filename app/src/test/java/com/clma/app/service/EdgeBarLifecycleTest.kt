package com.clma.app.service

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EdgeBarLifecycleTest {
    @Test fun persistCompletesBeforeAnyStartAndResumeCannotBypassIt() = runTest {
        val coordinator = EdgeBarEnableCoordinator()
        val disk = CompletableDeferred<Unit>()
        var enabled = false
        val commands = mutableListOf<Boolean>()
        launch {
            coordinator.setEnabled(true, { disk.await(); enabled = it }, { enabled }, commands::add)
        }
        runCurrent()
        launch { coordinator.reconcile({ enabled }, commands::add) }
        runCurrent()
        assertTrue(commands.isEmpty())
        disk.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(true, true), commands)
    }

    @Test fun rapidOnOffOnOffLeavesPersistedFalseAndRejectsQueuedStart() = runTest {
        val coordinator = EdgeBarEnableCoordinator()
        var enabled = false
        val writes = mutableListOf<Boolean>()
        val queuedCommands = mutableListOf<Boolean>()
        listOf(true, false, true, false).forEach { next ->
            launch {
                coordinator.setEnabled(next, { enabled = it; writes.add(it) }, { enabled }, queuedCommands::add)
            }
        }
        advanceUntilIdle()
        assertEquals(listOf(true, false, true, false), writes)
        assertEquals(writes, queuedCommands)
        assertFalse(enabled)
        // Deliver old STARTs after STOP: service checks the latest setting, not that old intent.
        queuedCommands.filter { it }.forEach { assertFalse(edgeBarMayStart(enabled, false)) }
    }

    @Test fun disabledSettingWinsOverResumeRestore() = runTest {
        val coordinator = EdgeBarEnableCoordinator()
        var enabled = true
        val commands = mutableListOf<Boolean>()
        coordinator.setEnabled(false, { enabled = it }, { enabled }, commands::add)
        coordinator.reconcile({ enabled }, commands::add)
        assertEquals(listOf(false, false), commands)
    }

    @Test fun failedSettingsWriteDoesNotSendACommand() = runTest {
        val coordinator = EdgeBarEnableCoordinator()
        val failure = IOException("disk failed")
        val commands = mutableListOf<Boolean>()
        val actual = runCatching {
            coordinator.setEnabled(true, { throw failure }, { false }, commands::add)
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertTrue(commands.isEmpty())
    }

    @Test fun serviceStartRequiresEnabledAndNotDestroyed() {
        assertTrue(edgeBarMayStart(true, false))
        assertFalse(edgeBarMayStart(false, false))
        assertFalse(edgeBarMayStart(true, true))
        assertFalse(edgeBarMayStart(false, true))
    }

    @Test fun normalAnimationIsUnchangedButDetachedAndStoppedViewsCannotRenew() {
        val gate = EdgeBarFrameGate()
        assertFalse(gate.canRequestFrame(true, true))
        gate.attach()
        assertTrue(gate.canRequestFrame(true, true))
        assertFalse(gate.canRequestFrame(false, true))
        assertFalse(gate.canRequestFrame(true, false))
        gate.detach()
        assertFalse(gate.canRequestFrame(true, true))
        gate.attach()
        assertTrue(gate.canRequestFrame(true, true))
        gate.stop()
        gate.attach()
        assertFalse(gate.canRequestFrame(true, true))
    }

    @Test fun removalFailureKeepsOwnershipAndStopsQueuedFramesBeforeRetry() {
        val gate = EdgeBarFrameGate().apply { attach() }
        val view = Any()
        var owned: Any? = view
        val order = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        var queuedFrames = 1
        fun release(fail: Boolean) {
            val removed = releaseOverlayResource(owned,
                quiesce = { gate.stop(); order.add("stop") },
                remove = { order.add("remove"); if (fail) throw IOException("remove") },
                onFailure = failures::add,
            )
            if (removed) owned = null
        }
        release(true)
        assertSame(view, owned)
        // Execute the invalidation that was already queued when shutdown started.
        queuedFrames--
        if (gate.canRequestFrame(true, true)) queuedFrames++
        assertEquals(0, queuedFrames)
        release(false)
        release(false) // Repeated destruction is a no-op once ownership is released.
        assertEquals(null, owned)
        assertEquals(listOf("stop", "remove", "stop", "remove"), order)
        assertEquals(1, failures.size)
    }

    @Test fun failedShowWithNoDialogStillReleasesProbeAndCallbacks() {
        var dialog: Any? = null // Probe was installed, then Dialog.show threw.
        var probe: Any? = Any()
        var timerRunning = true
        var listenerRegistered = true
        val failures = mutableListOf<Throwable>()
        repeat(2) {
            cleanupOverlayResources(failures::add,
                { timerRunning = false },
                { dialog = null },
                { if (probe != null) probe = null },
                { listenerRegistered = false },
            )
        }
        assertEquals(null, dialog)
        assertEquals(null, probe)
        assertFalse(timerRunning)
        assertFalse(listenerRegistered)
        assertTrue(failures.isEmpty())
    }

    @Test fun oneRemovalFailureDoesNotSkipOtherResources() {
        val order = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        cleanupOverlayResources(failures::add,
            { order.add("timer") },
            { order.add("dialog"); throw IOException("dialog remove") },
            { order.add("ime") },
            { order.add("blur") },
            { order.add("unbind") },
        )
        assertEquals(listOf("timer", "dialog", "ime", "blur", "unbind"), order)
        assertEquals(1, failures.size)
    }

    @Test fun registeredButNotYetAttachedResourceIsStillRemoved() {
        data class Window(var registered: Boolean = true, val attached: Boolean = false)
        val partial = Window()
        var quiesced = false
        val failures = mutableListOf<Throwable>()
        assertTrue(releaseOverlayResource(partial,
            quiesce = { quiesced = true },
            remove = { it.registered = false },
            onFailure = failures::add,
        ))
        assertTrue(quiesced)
        assertFalse(partial.registered)
        assertFalse(partial.attached)
        assertTrue(failures.isEmpty())
    }

    @Test fun quiesceFailureStillAttemptsRemoval() {
        var removed = false
        val failures = mutableListOf<Throwable>()
        assertTrue(releaseOverlayResource(Any(),
            quiesce = { throw IOException("quiesce") },
            remove = { removed = true },
            onFailure = failures::add,
        ))
        assertTrue(removed)
        assertEquals(1, failures.size)
    }
}
