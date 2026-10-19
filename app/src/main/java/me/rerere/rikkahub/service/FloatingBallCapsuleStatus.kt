package me.rerere.rikkahub.service

/**
 * What the status capsule's second line says. Pure, so the service only maps it to strings.
 *
 *  - [Kind.WORKING]: a turn is running; [elapsed] ticks up from when it started.
 *  - [Kind.DONE]: the last turn finished; [elapsed] is how long it took. Shown until the user
 *    next opens the panel or a new turn starts.
 *  - [Kind.READY]: nothing to report; the capsule invites a tap.
 */
internal data class CapsuleStatus(val kind: Kind, val elapsed: String? = null) {
    enum class Kind { READY, WORKING, DONE }
}

internal fun capsuleStatus(
    working: Boolean,
    workingSinceMs: Long?,
    nowMs: Long,
    lastTurnMs: Long?,
): CapsuleStatus = when {
    working && workingSinceMs != null ->
        CapsuleStatus(CapsuleStatus.Kind.WORKING, formatCapsuleElapsed(nowMs - workingSinceMs))
    working -> CapsuleStatus(CapsuleStatus.Kind.WORKING)
    lastTurnMs != null -> CapsuleStatus(CapsuleStatus.Kind.DONE, formatCapsuleElapsed(lastTurnMs))
    else -> CapsuleStatus(CapsuleStatus.Kind.READY)
}

/** `0:07`, `1:05`, `1:02:03`. Negative input (clock skew) reads as `0:00`. */
internal fun formatCapsuleElapsed(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}
