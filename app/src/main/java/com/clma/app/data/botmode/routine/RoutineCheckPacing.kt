// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/routine-check-pacing.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.routine

import kotlinx.serialization.Serializable

private const val DAY_MS = 86_400_000L
private const val CHECK_RUNS_PER_DAY = 24

@Serializable
data class RoutineCheckPacing(
    val admitted: List<Long> = emptyList(),
    val failures: Int = 0,
    val backoffUntil: Long = 0,
) {
    fun allows(now: Long): Boolean =
        now >= backoffUntil && admitted.count { it > now - DAY_MS } < CHECK_RUNS_PER_DAY

    fun admit(now: Long): RoutineCheckPacing = copy(admitted = admitted.filter { it > now - DAY_MS } + now)

    fun settle(now: Long, failed: Boolean, seed: String): RoutineCheckPacing {
        if (!failed) return copy(failures = 0, backoffUntil = 0)
        val count = (failures + 1).coerceAtMost(5)
        val wait = minOf(4 * 60 * 60_000L, 15 * 60_000L * (1 shl (count - 1)))
        val jitter = 0.8 + (seed.hashCode().toUInt() % 1000u).toInt() / 2500.0
        return copy(failures = count, backoffUntil = now + (wait * jitter).toLong())
    }
}
