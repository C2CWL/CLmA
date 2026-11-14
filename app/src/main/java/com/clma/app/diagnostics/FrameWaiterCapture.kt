package com.clma.app.diagnostics

import java.util.IdentityHashMap

/** Pure bounded recorder: counts registrations, NOT frames, draws or CPU samples. */
internal class FrameWaiterCapture(
    private val startedMs: Long,
    private val stack: () -> Array<StackTraceElement>,
) {
    companion object {
        const val WINDOW_MS = 10_000L
        const val MAX_SOURCES = 32
        const val MAX_TAGS = 64
        const val MAX_STACKS = 8
        const val MAX_DEPTH = 16
        fun increment(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1
    }
    private class Source(val key: String, val category: String) {
        var siteCalls = 0L
        var registrations = 0L
        var stackChecked = false
        var trace: List<String>? = null
    }
    @Volatile var accepting = true
        private set
    private var reason = "running"
    private var endedMs = startedMs
    private var failed = false
    private var siteCalls = 0L
    private var registrations = 0L
    private var overflowSources = 0L
    private var evictedTags = 0L
    private var stackCount = 0
    private val sources = linkedMapOf<String, Source>()
    private val overflow = Source("overflow", "overflow")
    // Identity only: never call a callback's equals, hashCode or toString; at most 64 references.
    private val tags = IdentityHashMap<Any, Source>()

    private fun checkTime(now: Long): Boolean {
        if (accepting && now - startedMs >= WINDOW_MS) end("expired", startedMs + WINDOW_MS)
        return accepting
    }
    private fun source(key: String, category: String): Source {
        val bounded = key.take(256)
        sources[bounded]?.let { return it }
        if (sources.size >= MAX_SOURCES) {
            overflowSources = increment(overflowSources)
            return overflow
        }
        return Source(bounded, category.take(40)).also { sources[bounded] = it }
    }
    @Synchronized fun site(callback: Any?, key: String, category: String, now: Long) {
        if (!checkTime(now)) return
        siteCalls = increment(siteCalls)
        val source = source(key, category)
        source.siteCalls = increment(source.siteCalls)
        if (callback != null) {
            if (!tags.containsKey(callback) && tags.size >= MAX_TAGS) {
                tags.remove(tags.keys.iterator().next())
                evictedTags = increment(evictedTags)
            }
            tags[callback] = source
        }
    }
    @Synchronized fun await(callback: Any?, now: Long) {
        if (!checkTime(now)) return
        registrations = increment(registrations)
        val source = tags.remove(callback) ?: source("unattributed:" + (callback?.javaClass?.name ?: "null"), "unattributed")
        source.registrations = increment(source.registrations)
        if (!source.stackChecked) {
            source.stackChecked = true
            if (stackCount < MAX_STACKS) {
                stackCount++
                // Class/method/line only. No callback values, file paths, messages or exceptions.
                source.trace = stack().asSequence()
                    .filterNot { it.className == Thread::class.java.name || it.className.startsWith("com.clma.app.diagnostics.FrameWaiter") }
                    .take(MAX_DEPTH)
                    .map { "${it.className.take(128)}.${it.methodName.take(96)}:${it.lineNumber}" }
                    .toList()
            }
        }
    }
    @Synchronized fun fail(now: Long) {
        failed = true
        end("hook_error", now)
    }
    private fun end(why: String, now: Long) {
        if (!accepting) return
        accepting = false
        reason = why
        endedMs = now
    }
    @Synchronized fun finish(why: String, now: Long): List<String> {
        checkTime(now)
        end(why, now)
        tags.clear()
        val all = sources.values.toList() + listOf(overflow).filter { it.siteCalls > 0 || it.registrations > 0 }
        val lines = mutableListOf(
            "status=done hook=broadcast_entry_v1 reason=$reason window_ms=${(endedMs - startedMs).coerceIn(0L, WINDOW_MS)} " +
                "registrations=$registrations site_calls=$siteCalls sources=${sources.size} stacks=$stackCount " +
                "overflow_events=$overflowSources evicted_tags=$evictedTags failed=${if (failed) 1 else 0} " +
                "scope=broadcast_waiters_and_tagged_sites not_draw_count=1",
        )
        all.forEachIndexed { index, source ->
            lines += "source=$index category=${source.category} site=${source.key} registrations=${source.registrations} site_calls=${source.siteCalls}"
            source.trace?.let { lines += "source=$index first_registration_stack=" + it.joinToString(" <- ") }
        }
        return lines
    }
}

/** Once per explicit diagnostics enable (or cold process start if persisted on), not per page/job. */
internal class FrameWaiterControl(
    private val now: () -> Long,
    private val stack: () -> Array<StackTraceElement>,
) {
    private var enabled = false
    private var attempted = false
    private var owner: Any? = null
    @Volatile var active: FrameWaiterCapture? = null
        private set

    @Synchronized fun configure(value: Boolean): List<String>? {
        if (enabled == value) return null
        enabled = value
        if (value) {
            attempted = false
            return null
        }
        return stopCurrent("disabled")
    }
    @Synchronized fun claim(token: Any): Boolean {
        if (!enabled || attempted || owner != null) return false
        attempted = true
        owner = token
        return true
    }
    @Synchronized fun begin(token: Any): Boolean {
        if (!enabled || owner !== token || active != null) return false
        active = FrameWaiterCapture(now(), stack)
        return true
    }
    @Synchronized fun stop(token: Any, reason: String): List<String>? =
        if (owner === token) stopCurrent(reason) else null

    private fun stopCurrent(reason: String): List<String>? {
        val capture = active
        active = null // Disable the hot hook before making the single bounded report.
        val hadOwner = owner != null
        owner = null
        return capture?.finish(reason, now())
            ?: if (hadOwner) listOf("status=cancelled_before_start reason=$reason registrations=0") else null
    }
}
