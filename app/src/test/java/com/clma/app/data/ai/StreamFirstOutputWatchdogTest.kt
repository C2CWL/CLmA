package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the first-output watchdog: a stream that emits nothing must fail fast (so the retry policy
 * can re-issue it), while a stream that has already started must never be aborted - a long prefill
 * or a slow tail is not a dead socket.
 */
class StreamFirstOutputWatchdogTest {

    @Test
    fun `silent stream fails with the supplied timeout failure`() = runBlocking {
        val failure = IOException("silent")
        var caught: Throwable? = null
        try {
            flow<Int> { delay(60_000) }
                .abortIfSilentBeforeFirstElement(50L) { failure }
                .collect { }
        } catch (e: Throwable) {
            caught = e
        }
        // Type + message, not identity: kotlinx recovers stack traces by copying the exception,
        // so the instance the collector sees is not the one the watchdog built.
        assertTrue("expected an IOException, got $caught", caught is IOException)
        assertEquals("silent", caught?.message)
    }

    /**
     * The retry policy refuses to re-issue a request when the failure is a cancellation, so the
     * watchdog must never surface one - and it must be deterministic, not a race between its own
     * `close(cause)` and the cancellation of the upstream collection.
     */
    @Test
    fun `silent stream is always a retryable non-cancellation failure`() = runBlocking {
        repeat(50) {
            var caught: Throwable? = null
            try {
                flow<Int> { delay(60_000) }
                    .abortIfSilentBeforeFirstElement(5L) { IOException("silent") }
                    .collect { }
            } catch (e: Throwable) {
                caught = e
            }
            assertTrue("iteration $it: expected IOException, got $caught", caught is IOException)
            assertFalse(
                "iteration $it: must not be a cancellation (the retry policy would skip it)",
                caught is CancellationException,
            )
        }
    }

    @Test
    fun `silent stream cancels its upstream collection`() = runBlocking {
        var upstreamCancelled = false
        try {
            flow<Int> {
                try {
                    delay(60_000)
                } finally {
                    upstreamCancelled = true
                }
            }.abortIfSilentBeforeFirstElement(50L) { IOException("x") }.collect { }
        } catch (_: IOException) {
            // expected
        }
        assertTrue("upstream must be cancelled so the dead socket is released", upstreamCancelled)
    }

    @Test
    fun `emitting stream passes values through untouched`() = runBlocking {
        val seen = mutableListOf<Int>()
        flow { repeat(3) { emit(it); delay(5) } }
            .abortIfSilentBeforeFirstElement(5_000L) { IOException("never") }
            .collect { seen += it }
        assertEquals(listOf(0, 1, 2), seen)
    }

    @Test
    fun `a first element later than the timeout fails`() = runBlocking {
        var caught: Throwable? = null
        try {
            flow { delay(500); emit(1) }
                .abortIfSilentBeforeFirstElement(50L) { IOException("late") }
                .collect { }
        } catch (e: Throwable) {
            caught = e
        }
        assertTrue("late first element must fail, got $caught", caught is IOException)
    }

    @Test
    fun `watchdog disarms after the first element so a slow tail survives`() = runBlocking {
        // 250ms tail is far beyond the 50ms watchdog; the first element trains it off.
        val seen = mutableListOf<Int>()
        flow {
            emit(1)
            delay(250)
            emit(2)
        }.abortIfSilentBeforeFirstElement(50L) { IOException("tail") }
            .collect { seen += it }
        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun `timeout of zero disables the watchdog`() = runBlocking {
        val seen = mutableListOf<Int>()
        flow { emit(7) }
            .abortIfSilentBeforeFirstElement(0L) { IOException("never") }
            .collect { seen += it }
        assertEquals(listOf(7), seen)
    }

    // ---- two-phase form (abortIfSilent): the mid-reply stall -----------------------------------

    @Test
    fun `a reply that goes quiet after its first element fails with the stall failure`() = runBlocking {
        var caught: Throwable? = null
        try {
            flow<Int> {
                emit(1)
                delay(60_000)
            }.abortIfSilent(
                firstElementTimeoutMs = 5_000L,
                interChunkTimeoutMs = 50L,
                noFirstElement = { IOException("no first element") },
                stalled = { IOException("stalled") },
            ).collect { }
        } catch (e: Throwable) {
            caught = e
        }
        assertTrue("expected an IOException, got $caught", caught is IOException)
        assertEquals("stalled", caught?.message)
    }

    @Test
    fun `a stalled reply cancels its upstream collection`() = runBlocking {
        var upstreamCancelled = false
        try {
            flow<Int> {
                try {
                    emit(1)
                    delay(60_000)
                } finally {
                    upstreamCancelled = true
                }
            }.abortIfSilent(
                firstElementTimeoutMs = 5_000L,
                interChunkTimeoutMs = 50L,
                noFirstElement = { IOException("no first element") },
                stalled = { IOException("stalled") },
            ).collect { }
        } catch (_: IOException) {
            // expected
        }
        assertTrue("upstream must be cancelled so the stalled socket is released", upstreamCancelled)
    }

    @Test
    fun `a slow but alive reply is never aborted`() = runBlocking {
        val seen = mutableListOf<Int>()
        flow {
            repeat(6) {
                emit(it)
                delay(40)
            }
        }.abortIfSilent(
            firstElementTimeoutMs = 5_000L,
            interChunkTimeoutMs = 150L,
            noFirstElement = { IOException("no first element") },
            stalled = { IOException("stalled") },
        ).collect { seen += it }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), seen)
    }

    /** The budget is per gap, never a cap on the total reply: three 120ms gaps, budget 200ms. */
    @Test
    fun `the stall budget is per gap and not a cap on the whole reply`() = runBlocking {
        val seen = mutableListOf<Int>()
        flow {
            emit(1)
            delay(120)
            emit(2)
            delay(120)
            emit(3)
        }.abortIfSilent(
            firstElementTimeoutMs = 5_000L,
            interChunkTimeoutMs = 200L,
            noFirstElement = { IOException("no first element") },
            stalled = { IOException("stalled") },
        ).collect { seen += it }
        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test
    fun `stall detection still applies when the first output phase is disabled`() = runBlocking {
        var caught: Throwable? = null
        try {
            flow<Int> {
                delay(120) // 0 = no budget at all before the first element
                emit(1)
                delay(60_000)
            }.abortIfSilent(
                firstElementTimeoutMs = 0L,
                interChunkTimeoutMs = 50L,
                noFirstElement = { IOException("no first element") },
                stalled = { IOException("stalled") },
            ).collect { }
        } catch (e: Throwable) {
            caught = e
        }
        assertEquals("stalled", caught?.message)
    }

    @Test
    fun `a disabled stall budget leaves a quiet reply alone`() = runBlocking {
        val seen = mutableListOf<Int>()
        flow {
            emit(1)
            delay(150)
            emit(2)
        }.abortIfSilent(
            firstElementTimeoutMs = 50L,
            interChunkTimeoutMs = 0L,
            noFirstElement = { IOException("no first element") },
            stalled = { IOException("stalled") },
        ).collect { seen += it }
        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun `a stream that never starts still reports the first element phase`() = runBlocking {
        var caught: Throwable? = null
        try {
            flow<Int> { delay(60_000) }
                .abortIfSilent(
                    firstElementTimeoutMs = 50L,
                    interChunkTimeoutMs = 150L,
                    noFirstElement = { IOException("no first element") },
                    stalled = { IOException("stalled") },
                ).collect { }
        } catch (e: Throwable) {
            caught = e
        }
        assertEquals("no first element", caught?.message)
    }

    @Test
    fun `both phases off passes the stream through unchanged`() = runBlocking {
        val seen = mutableListOf<Int>()
        flow {
            emit(1)
            delay(100)
            emit(2)
        }.abortIfSilent(
            firstElementTimeoutMs = 0L,
            interChunkTimeoutMs = 0L,
            noFirstElement = { IOException("no first element") },
            stalled = { IOException("stalled") },
        ).collect { seen += it }
        assertEquals(listOf(1, 2), seen)
    }
}
