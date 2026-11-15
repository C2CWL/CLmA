package com.clma.app.service

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
        var enabled = false
        val commands = mutableListOf<Boolean>()
        val failure = IOException("persist")
        val actual = runCatching {
            coordinator.setEnabled(true, { throw failure }, { enabled }, commands::add)
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

    @Test fun idleAndFinishedJobsNeverKeepTheBarWorking() {
        assertFalse(edgeBarHasWork(emptyList()))
        assertFalse(edgeBarHasWork(listOf(null)))
        val done = Job().apply { complete() }
        val cancelled = Job().apply { cancel() }
        assertFalse(edgeBarHasWork(listOf(done, cancelled, null)))
        val active = Job()
        assertTrue(edgeBarHasWork(listOf(done, active)))
        active.complete()
        assertFalse(edgeBarHasWork(listOf(done, active)))
    }

    @Test fun lazyGenerationCountsBeforeStartAndCancelledTurnStopsImmediately() = runTest {
        val turn = launch(start = CoroutineStart.LAZY) { }
        assertTrue(edgeBarHasWork(listOf(turn)))
        turn.cancel()
        assertFalse(edgeBarHasWork(listOf(turn)))
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
        var probe: Any? = Any()
        var callbacks: Any? = Any()
        val failures = mutableListOf<Throwable>()
        val removed = releaseOverlayResource(Unit,
            quiesce = { throw IOException("quiesce") },
            remove = { probe = null; callbacks = null },
            onFailure = failures::add,
        )
        assertTrue(removed)
        assertEquals(null, probe)
        assertEquals(null, callbacks)
        assertEquals(1, failures.size)
    }

    @Test fun oneCleanupFailureDoesNotSkipTheRest() {
        val done = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        cleanupOverlayResources(failures::add,
            { done.add("first") },
            { throw IOException("second") },
            { done.add("third") },
        )
        assertEquals(listOf("first", "third"), done)
        assertEquals(1, failures.size)
    }

    @Test fun nullResourceReleaseIsAlreadyComplete() {
        assertTrue(releaseOverlayResource<Any>(null, { error("quiesce") }, { error("remove") }) { error("failure") })
    }

    @Test fun successfulRemovalDropsOwnership() {
        var owned: Any? = Any()
        val removed = releaseOverlayResource(owned,
            quiesce = { },
            remove = { },
            onFailure = { error("unexpected") },
        )
        assertTrue(removed)
    }

    @Test fun failedRemovalReportsTheSameFailure() {
        val failure = IOException("remove")
        val failures = mutableListOf<Throwable>()
        val removed = releaseOverlayResource(Any(),
            quiesce = { },
            remove = { throw failure },
            onFailure = failures::add,
        )
        assertFalse(removed)
        assertEquals(listOf(failure), failures)
    }

    @Test fun stoppedGateStaysStoppedAfterReattach() {
        val gate = EdgeBarFrameGate().apply { attach(); stop(); detach(); attach() }
        assertFalse(gate.canRequestFrame(true, true))
    }

    @Test fun cleanupContinuesAfterEveryFailure() {
        val failures = mutableListOf<Throwable>()
        cleanupOverlayResources(failures::add, { throw IOException("a") }, { throw IOException("b") })
        assertEquals(2, failures.size)
    }

    @Test fun releaseReportsRemoveFailureEvenIfQuiesceFails() {
        val failures = mutableListOf<Throwable>()
        assertFalse(releaseOverlayResource(Any(),
            quiesce = { throw IOException("quiesce") },
            remove = { throw IOException("remove") },
            onFailure = failures::add,
        ))
        assertEquals(2, failures.size)
    }

    @Test fun coordinatorReconcileAppliesThePersistedValue() = runTest {
        val coordinator = EdgeBarEnableCoordinator()
        val commands = mutableListOf<Boolean>()
        coordinator.reconcile({ true }, commands::add)
        assertEquals(listOf(true), commands)
    }

    @Test fun releaseOfMissingResourceDoesNotInvokeCallbacks() {
        assertTrue(releaseOverlayResource<String>(null, { error("q") }, { error("r") }, { error("f") }))
    }

    @Test fun gateRejectsEveryDisabledCombination() {
        val gate = EdgeBarFrameGate()
        assertFalse(gate.canRequestFrame(false, false))
        gate.attach()
        assertFalse(gate.canRequestFrame(true, false))
        assertFalse(gate.canRequestFrame(false, true))
    }

    @Test fun cleanupWithNoActionsReportsNoFailures() {
        val failures = mutableListOf<Throwable>()
        cleanupOverlayResources(failures::add)
        assertTrue(failures.isEmpty())
    }

    @Test fun successfulReleaseInvokesQuiesceBeforeRemove() {
        val order = mutableListOf<String>()
        assertTrue(releaseOverlayResource(Any(),
            quiesce = { order.add("quiesce") },
            remove = { order.add("remove") },
            onFailure = { error("failure") },
        ))
        assertEquals(listOf("quiesce", "remove"), order)
    }
}
