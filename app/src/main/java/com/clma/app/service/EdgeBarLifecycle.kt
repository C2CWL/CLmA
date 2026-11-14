package com.clma.app.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes persisted user intent and its service command, including resume/boot restores. */
internal class EdgeBarEnableCoordinator {
    private val mutex = Mutex()

    suspend fun setEnabled(
        enabled: Boolean,
        persist: suspend (Boolean) -> Unit,
        readEnabled: suspend () -> Boolean,
        apply: (Boolean) -> Unit,
    ) = mutex.withLock {
        persist(enabled)
        apply(readEnabled()) // Never start ahead of a successful settings write.
    }

    suspend fun reconcile(readEnabled: suspend () -> Boolean, apply: (Boolean) -> Unit) =
        mutex.withLock { apply(readEnabled()) }
}

/** A stopped view cannot be revived by a queued invalidation or a later attachment. */
internal class EdgeBarFrameGate {
    private var attached = false
    private var stopped = false
    fun attach() { attached = true }
    fun detach() { attached = false }
    fun stop() { stopped = true }
    fun canRequestFrame(working: Boolean, animatorsEnabled: Boolean): Boolean =
        attached && !stopped && working && animatorsEnabled
}

internal fun edgeBarMayStart(enabled: Boolean, destroyed: Boolean): Boolean = enabled && !destroyed

/** One failure must not prevent the other independent resources from being released. */
internal fun cleanupOverlayResources(onFailure: (Throwable) -> Unit, vararg actions: () -> Unit) {
    actions.forEach { action -> runCatching(action).onFailure(onFailure) }
}

/** Keep ownership on failure; always quiesce before asking WindowManager to remove a view. */
internal fun <T : Any> releaseOverlayResource(
    resource: T?,
    quiesce: (T) -> Unit,
    remove: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
): Boolean {
    if (resource == null) return true
    runCatching { quiesce(resource) }.onFailure(onFailure)
    return runCatching { remove(resource) }.onFailure(onFailure).isSuccess
}
