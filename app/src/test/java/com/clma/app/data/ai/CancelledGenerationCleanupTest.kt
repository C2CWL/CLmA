package com.clma.app.data.ai

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class CancelledGenerationCleanupTest {
    @Test
    fun `cleanup runs after caller cancellation`() = runBlocking {
        var finalized = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                finalizeCancelledGeneration { finalized = true }
            }
        }
        job.cancelAndJoin()
        assertTrue(finalized)
    }

    @Test
    fun `full channel cannot hold cancellation cleanup forever`() = runBlocking {
        val channel = Channel<Unit>(1)
        channel.send(Unit)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                finalizeCancelledGeneration { channel.send(Unit) }
            }
        }
        withTimeout(5_000L) { job.cancelAndJoin() }
        assertTrue(job.isCompleted)
        channel.close()
    }
}
