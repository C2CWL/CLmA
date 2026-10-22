package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The reference-counting contract behind the shared foreground-work resource.
 *
 * P2-08 — a sub-agent run now holds this tracker for its whole life (via
 * [ChatService.retainForegroundForActiveRun]), not only the generations do. That makes the
 * cases below load-bearing for the keep-alive red line: the resource must start on the first
 * claim and stop only on the last, so a finished orchestration can never leave the service
 * resident, and an orchestration ending can never stop a service a live generation needs.
 *
 * Pure JVM: [ForegroundWorkTracker] imports nothing from Android.
 */
class ForegroundWorkTrackerTest {

    /** Counts how often the shared resource was started and stopped. */
    private class Recorder {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)

        fun tracker(): ForegroundWorkTracker = ForegroundWorkTracker(
            onFirstAcquire = { starts.incrementAndGet() },
            onLastRelease = { stops.incrementAndGet() },
        )
    }

    @Test
    fun `resource remains active until every operation releases`() {
        val events = mutableListOf<String>()
        val tracker = ForegroundWorkTracker(
            onFirstAcquire = { events += "start" },
            onLastRelease = { events += "stop" },
        )

        val releaseFirst = tracker.acquire()
        val releaseSecond = tracker.acquire()
        releaseFirst()

        assertEquals(listOf("start"), events)

        releaseSecond()
        assertEquals(listOf("start", "stop"), events)
    }

    @Test
    fun `releasing an operation twice does not stop another operation`() {
        val events = mutableListOf<String>()
        val tracker = ForegroundWorkTracker(
            onFirstAcquire = { events += "start" },
            onLastRelease = { events += "stop" },
        )

        val releaseFirst = tracker.acquire()
        val releaseSecond = tracker.acquire()
        releaseFirst()
        releaseFirst()
        releaseSecond()

        assertEquals(listOf("start", "stop"), events)
    }

    // ---- P2-08 — a sub-agent run holds the same tracker, so these cases now matter ----------

    @Test
    fun `the read-only active count tracks the live claims`() {
        val recorder = Recorder()
        val tracker = recorder.tracker()
        assertEquals(0, tracker.activeCount)

        val first = tracker.acquire()
        val second = tracker.acquire()
        assertEquals(2, tracker.activeCount)

        first()
        assertEquals(1, tracker.activeCount)
        second()
        assertEquals(0, tracker.activeCount)
    }

    @Test
    fun `claims release in an order other than the one they were taken in`() {
        val recorder = Recorder()
        val tracker = recorder.tracker()

        val a = tracker.acquire()
        val b = tracker.acquire()
        val c = tracker.acquire()
        assertEquals(3, tracker.activeCount)

        b()
        assertEquals(2, tracker.activeCount)
        assertEquals(0, recorder.stops.get())

        c()
        a()
        assertEquals(0, tracker.activeCount)
        assertEquals(1, recorder.starts.get())
        assertEquals(1, recorder.stops.get())
    }

    @Test
    fun `a fresh claim restarts a stopped tracker`() {
        val recorder = Recorder()
        val tracker = recorder.tracker()

        val a = tracker.acquire()
        val b = tracker.acquire()
        a()
        b()
        assertEquals(1, recorder.stops.get())

        val c = tracker.acquire()
        assertEquals(2, recorder.starts.get())
        c()
        assertEquals(2, recorder.stops.get())
    }

    @Test
    fun `a duplicated release cannot stop the resource while another claim is live`() {
        val recorder = Recorder()
        val tracker = recorder.tracker()

        val orchestrator = tracker.acquire()
        val generation = tracker.acquire()
        // A run that leaked its thunk into two finally blocks must not take the service down.
        orchestrator()
        orchestrator()
        assertEquals(1, tracker.activeCount)
        assertEquals(0, recorder.stops.get())

        generation()
        assertEquals(1, recorder.stops.get())
    }

    @Test
    fun `many holders that all arrive before any leaves keep the resource up once`() {
        val recorder = Recorder()
        val tracker = recorder.tracker()
        val holders = 8
        val pool = Executors.newFixedThreadPool(holders)
        val allHeld = CountDownLatch(holders)
        val releaseThem = CountDownLatch(1)

        try {
            val futures = (0 until holders).map {
                pool.submit(Callable {
                    val release = tracker.acquire()
                    allHeld.countDown()
                    releaseThem.await(5, TimeUnit.SECONDS)
                    release
                })
            }

            assertTrue("every holder should have acquired", allHeld.await(5, TimeUnit.SECONDS))
            assertEquals(holders, tracker.activeCount)
            assertEquals(1, recorder.starts.get())
            assertEquals(0, recorder.stops.get())

            releaseThem.countDown()
            futures.map { it.get(5, TimeUnit.SECONDS) }.forEach { release -> release() }

            assertEquals(0, tracker.activeCount)
            assertEquals(1, recorder.stops.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent acquire and release cycles always end at zero with balanced start and stop`() {
        val recorder = Recorder()
        val tracker = recorder.tracker()
        val workers = 8
        val cycles = 200
        val pool = Executors.newFixedThreadPool(workers)

        try {
            val done = CountDownLatch(workers)
            repeat(workers) {
                pool.submit(Runnable {
                    try {
                        repeat(cycles) {
                            // Overlapping lifetimes: hold one claim while taking another.
                            val outer = tracker.acquire()
                            val inner = tracker.acquire()
                            inner()
                            outer()
                        }
                    } finally {
                        done.countDown()
                    }
                })
            }
            assertTrue("workers should finish", done.await(10, TimeUnit.SECONDS))
            assertEquals(0, tracker.activeCount)
            assertEquals(recorder.starts.get(), recorder.stops.get())
            assertTrue(recorder.starts.get() >= 1)
        } finally {
            pool.shutdownNow()
        }
    }
}
