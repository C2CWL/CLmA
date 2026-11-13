package com.clma.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GenerationCompletionPersistenceTest {
    @Test
    fun `cancelled generation finishes while busy persistence retries latest state`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val mutex = Mutex(locked = true)
            val entered = CompletableDeferred<Unit>()
            val saved = CompletableDeferred<String>()
            var current = "partial"
            try {
                val generation =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            persistGenerationCompletion(scope, timeoutMillis = 50) {
                                entered.complete(Unit)
                                mutex.withLock { saved.complete(current) }
                            }
                        }
                    }
                generation.cancel()
                entered.await()
                withTimeout(2_000) { generation.join() }
                assertFalse(saved.isCompleted)
                current = "newer state"
                mutex.unlock()
                assertEquals("newer state", withTimeout(2_000) { saved.await() })
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `cancelled persistence scope still releases ownership`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.cancel()
            val released = CompletableDeferred<Unit>()
            persistGenerationCompletion(scope, onFinished = { released.complete(Unit) }) {
                error("Cancelled scope must not write")
            }
            withTimeout(2_000) { released.await() }
        }

    @Test
    fun `quick final write runs only once`() =
        runBlocking {
            var writes = 0
            persistGenerationCompletion(this) { writes++ }
            assertEquals(1, writes)
        }
}
