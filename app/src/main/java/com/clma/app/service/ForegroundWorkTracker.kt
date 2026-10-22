package me.rerere.rikkahub.service

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a shared foreground-work resource alive while one or more chat operations are active.
 * Each [acquire] call returns an idempotent release callback so cancellation and normal
 * completion can race without stopping the resource for another still-running operation.
 *
 * P2-08 — the holders are no longer only chat generations. A sub-agent run holds this same
 * tracker for its entire life (see [ChatService.retainForegroundForActiveRun]): a background
 * dispatch can outlive the parent turn that started it, and the gaps between its model calls
 * (tool execution, waiting for a concurrency slot, the parent-notification wait) would
 * otherwise run with no foreground claim at all. Sharing one counter keeps both ends of the
 * red line intact — the resource starts on the first claim and only stops on the last, so an
 * orchestration can never tear down a live generation's service, and a counter that has
 * dropped to zero can never leave the service resident.
 */
internal class ForegroundWorkTracker(
    private val onFirstAcquire: () -> Unit,
    private val onLastRelease: () -> Unit,
) {
    private val lock = Any()
    private var holders = 0

    /** Claims currently held. Read-only observability for the P2-08 keep-alive and its tests. */
    val activeCount: Int
        get() = synchronized(lock) { holders }

    fun acquire(): () -> Unit {
        synchronized(lock) {
            if (holders++ == 0) onFirstAcquire()
        }

        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) {
                    check(holders > 0) { "Foreground work count underflow" }
                    if (--holders == 0) onLastRelease()
                }
            }
        }
    }
}
