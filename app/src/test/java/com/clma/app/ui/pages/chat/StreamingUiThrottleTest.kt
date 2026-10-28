package com.clma.app.ui.pages.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class StreamingUiThrottleTest {
    private fun gate() = StreamingUiGate<String>(80) { a, b -> b.startsWith(a) }

    @Test fun `first update is immediate and pending uses latest value`() {
        val gate = gate()
        assertEquals("a", gate.onNext("a", 0))
        assertNull(gate.onNext("ab", 10))
        assertNull(gate.onNext("abc", 20))
        assertEquals(60L, gate.millisUntilFlush(20))
        assertEquals("abc", gate.flush(80))
        assertNull(gate.millisUntilFlush(80))
    }

    @Test fun `contraction compared to pending is immediate even if longer than published`() {
        val gate = gate()
        gate.onNext("a", 0)
        gate.onNext("abcd", 10)
        assertEquals("abc", gate.onNext("abc", 20))
        assertNull(gate.flush(80))
    }

    @Test fun `structure change drops pending and starts fresh window`() {
        val gate = gate()
        gate.onNext("a", 0)
        gate.onNext("ab", 10)
        assertEquals("new", gate.onNext("new", 20))
        assertNull(gate.onNext("new!", 30))
        assertEquals(70L, gate.millisUntilFlush(30))
        assertEquals("new!", gate.flush(100))
    }

    @Test fun `flow completion flushes final value`() = runBlocking {
        val actual = flow { emit("a"); emit("ab"); emit("abc") }
            .throttleStreamingUpdates(60_000) { a, b -> b.startsWith(a) }.toList()
        assertEquals(listOf("a", "abc"), actual)
    }

    @Test fun `silent upstream still publishes trailing value`() = runBlocking {
        withTimeout(5_000) {
            val actual = flow { emit("a"); emit("ab"); awaitCancellation() }
                .throttleStreamingUpdates(20) { a, b -> b.startsWith(a) }.take(2).toList()
            assertEquals(listOf("a", "ab"), actual)
        }
    }

    @Test fun `downstream cancellation stops upstream`() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        withTimeout(5_000) {
            flow {
                try { emit("a"); awaitCancellation() }
                finally { stopped.complete(Unit) }
            }.throttleStreamingUpdates(80) { a, b -> b.startsWith(a) }.take(1).toList()
            stopped.await()
        }
    }

    @Test fun `upstream exception reaches downstream`() = runBlocking {
        try {
            flow { emit("a"); throw IllegalStateException("source failed") }
                .throttleStreamingUpdates(80) { a, b -> b.startsWith(a) }.toList()
            fail("exception must propagate")
        } catch (error: IllegalStateException) {
            assertEquals("source failed", error.message)
        }
    }
}
