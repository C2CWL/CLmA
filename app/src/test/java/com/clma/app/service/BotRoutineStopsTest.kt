package com.clma.app.service

import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BotRoutineStopsTest {
    @Test
    fun pauseCancelsOnlyMatchingRoutines() = runBlocking {
        val bot = Uuid.random()
        val other = Uuid.random()
        val started = CompletableDeferred<Unit>()
        val finishOther = CompletableDeferred<String>()
        val cancelled = async {
            BotRoutineStops.run(bot, { false }) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val unaffected = async {
            BotRoutineStops.run(other, { false }) { finishOther.await() }
        }

        started.await()
        BotRoutineStops.stop(setOf(bot))
        finishOther.complete("finished")
        assertNull(cancelled.await())
        assertEquals("finished", unaffected.await())
    }

    @Test
    fun pausedBotDoesNotStartARegisteredRoutine() = runBlocking {
        assertNull(BotRoutineStops.run(Uuid.random(), { true }) { error("Paused bot started") })
    }
}
