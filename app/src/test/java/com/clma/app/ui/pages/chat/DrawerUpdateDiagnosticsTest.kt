package com.clma.app.ui.pages.chat

import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class DrawerUpdateDiagnosticsTest {
    private fun metadata() = DrawerConversationMetadata(Uuid.random(), Uuid.random(), true)

    @Test fun `disabled counters do not record commits`() {
        val counters = DrawerUpdateCounters()
        counters.recordCommit(metadata()); counters.recordRailCommit()
        val summary = counters.takeWindowSummary()
        assertTrue(summary.contains("drawer_root_commits=0"))
        assertTrue(summary.contains("rail_root_commits=0"))
    }

    @Test fun `window counts commits not draws resets deltas and retains discrete context`() {
        val counters = DrawerUpdateCounters(); counters.start()
        val first = metadata()
        counters.recordDrawerState("RAIL", "Closed", "Closed", false)
        counters.recordBusy(true); counters.recordBusy(true); counters.recordResumed(true)
        counters.recordCommit(first); counters.recordCommit(first)
        counters.recordCommit(first.copy(agentModeEnabled = false))
        counters.recordRailCommit()
        val summary = counters.takeWindowSummary()
        assertTrue(summary.contains("drawer_root_commits=3"))
        assertTrue(summary.contains("rail_root_commits=1"))
        assertTrue(summary.contains("metadata_changes=1"))
        assertTrue(summary.contains("busy_changes=1"))
        assertTrue(summary.contains("style=RAIL"))
        assertTrue(summary.contains("resumed=1"))
        assertFalse(summary.contains(first.conversationId.toString()))
        assertFalse(summary.contains(first.assistantId.toString()))
        val next = counters.takeWindowSummary()
        assertTrue(next.contains("drawer_root_commits=0"))
        assertTrue(next.contains("metadata_changes=0"))
        assertTrue(next.contains("busy=1"))
        counters.stop(); counters.recordCommit(first)
        assertTrue(counters.takeWindowSummary().contains("drawer_root_commits=0"))
    }
}
