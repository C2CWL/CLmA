package com.clma.app.ui.pages.chat

import java.util.concurrent.ConcurrentHashMap

/** Plain counters, never Compose state. Disabled unless functional diagnostics are enabled. */
internal class DrawerUpdateCounters {
    @Volatile private var enabled = false
    private var commits = 0L
    private var cardCommits = 0L
    private var railCommits = 0L
    private var metadataChanges = 0L
    private var stateChanges = 0L
    private var busyChanges = 0L
    private var metadata: DrawerConversationMetadata? = null
    private var style = "UNKNOWN"
    private var current = "UNKNOWN"
    private var target = "UNKNOWN"
    private var animating = false
    private var busy = false
    private var resumed = false
    private var windowStartedNs = System.nanoTime()

    @Synchronized fun start() {
        commits = 0; cardCommits = 0; railCommits = 0
        metadataChanges = 0; stateChanges = 0; busyChanges = 0
        metadata = null
        style = "UNKNOWN"; current = "UNKNOWN"; target = "UNKNOWN"
        animating = false; busy = false; resumed = false
        windowStartedNs = System.nanoTime()
        enabled = true
    }

    @Synchronized fun stop() {
        enabled = false
        metadata = null
    }

    private inline fun record(block: () -> Unit) {
        if (enabled) synchronized(this) { if (enabled) block() }
    }

    fun recordCommit(value: DrawerConversationMetadata) = record {
        commits++
        if (metadata != null && metadata != value) metadataChanges++
        metadata = value
    }

    fun recordCardCommit() = record { cardCommits++ }
    fun recordRailCommit() = record { railCommits++ }

    fun recordDrawerState(newStyle: String, newCurrent: String, newTarget: String, running: Boolean) = record {
        if (style != newStyle || current != newCurrent || target != newTarget || animating != running) stateChanges++
        style = newStyle; current = newCurrent; target = newTarget; animating = running
    }

    fun recordBusy(value: Boolean) = record {
        if (busy != value) busyChanges++
        busy = value
    }

    fun recordResumed(value: Boolean) = record { resumed = value }

    /** Snapshot and reset interval counters only; no IDs, text, offsets or call stacks. */
    @Synchronized fun takeWindowSummary(): String {
        val now = System.nanoTime()
        val counterWindowMs = (now - windowStartedNs) / 1_000_000
        val summary = "counter_window_ms=$counterWindowMs style=$style current=$current target=$target animating=${if (animating) 1 else 0} " +
            "busy=${if (busy) 1 else 0} resumed=${if (resumed) 1 else 0} " +
            "drawer_root_commits=$commits card_hero_commits=$cardCommits rail_root_commits=$railCommits " +
            "metadata_changes=$metadataChanges state_changes=$stateChanges busy_changes=$busyChanges"
        commits = 0; cardCommits = 0; railCommits = 0
        metadataChanges = 0; stateChanges = 0; busyChanges = 0
        windowStartedNs = now
        return summary
    }
}

/** Read only from the existing 10-second RenderDiag sampler: this adds no timer or frame clock. */
internal object DrawerUpdateDiagnostics {
    private val windows = ConcurrentHashMap.newKeySet<DrawerUpdateCounters>()

    fun register(counters: DrawerUpdateCounters) {
        counters.start()
        windows.add(counters)
    }

    fun unregister(counters: DrawerUpdateCounters) {
        windows.remove(counters)
        counters.stop()
    }

    fun takeWindowSummary(): String = windows.toList().let { entries ->
        "drawer_windows=${entries.size} " + entries.mapIndexed { index, counters ->
            "drawer$index{${counters.takeWindowSummary()}}"
        }.joinToString(" ")
    }
}
