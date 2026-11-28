// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), docs/pausing-bots.md,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlin.uuid.Uuid

/** Direct routines run tools outside a chat session and need their own stopping handle. */
internal object BotRoutineStops {
    private class BotPaused : CancellationException()

    private val running = mutableMapOf<Job, Uuid>()

    fun stop(botIds: Set<Uuid>) {
        val jobs = synchronized(running) { running.filterValues { it in botIds }.keys.toList() }
        jobs.forEach { it.cancel(BotPaused()) }
    }

    suspend fun <T> run(botId: Uuid, isPaused: suspend () -> Boolean, block: suspend () -> T): T? =
        supervisorScope {
            val task = async(start = CoroutineStart.LAZY) {
                if (isPaused()) null else block()
            }
            synchronized(running) { running[task] = botId }
            try {
                task.await()
            } catch (paused: BotPaused) {
                currentCoroutineContext().ensureActive()
                null
            } finally {
                synchronized(running) { running.remove(task) }
            }
        }
}
