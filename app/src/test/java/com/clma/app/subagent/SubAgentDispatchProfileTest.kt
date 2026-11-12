package com.clma.app.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentDispatchProfileTest {

    @Test
    fun `each profile gives a distinct delegation instruction`() {
        val guidance = SubAgentDispatchProfile.entries.map(SubAgentDispatchProfileRules::dispatchGuidance)
        assertEquals(5, guidance.toSet().size)
        assertTrue(guidance[0].contains("disabled; do all work inline"))
        assertTrue(guidance[1].contains("work inline by default"))
        assertTrue(guidance[2].contains("proactively delegate"))
        assertTrue(guidance[3].contains("prefer parallel delegation"))
        assertTrue(guidance[4].contains("dispatch them before"))
    }

    @Test
    fun `enabled profiles preserve safety and avoid pointless delegation`() {
        SubAgentDispatchProfile.entries.filter { it != SubAgentDispatchProfile.NEVER }.forEach {
            val guidance = SubAgentDispatchProfileRules.dispatchGuidance(it)
            assertTrue(guidance.contains("Trivial replies and inseparable tasks may stay inline"))
            assertTrue(guidance.contains("approval policy, and safety constraints"))
            assertTrue(guidance.contains("state the concrete blocker"))
        }
    }

    @Test
    fun `NEVER disables dispatch`() {
        assertFalse(SubAgentDispatchProfileRules.isDispatchEnabled(SubAgentDispatchProfile.NEVER))
    }

    @Test
    fun `all non-NEVER profiles enable dispatch`() {
        SubAgentDispatchProfile.entries.filter { it != SubAgentDispatchProfile.NEVER }.forEach {
            assertTrue("$it should enable dispatch", SubAgentDispatchProfileRules.isDispatchEnabled(it))
        }
    }

    @Test
    fun `NEVER caps are all zero`() {
        val c = SubAgentDispatchProfileRules.caps(SubAgentDispatchProfile.NEVER)
        assertEquals(0, c.globalCap)
        assertEquals(0, c.perAssistantCap)
        assertEquals(0L, c.slotWaitMs)
    }

    @Test
    fun `NORMAL has increased per-assistant default`() {
        val c = SubAgentDispatchProfileRules.caps(SubAgentDispatchProfile.NORMAL)
        assertEquals(30, c.globalCap)
        assertEquals(8, c.perAssistantCap)
        assertEquals(30_000L, c.slotWaitMs)
        assertEquals(60, c.maxTripsCeiling)
        assertEquals(1800, c.timeoutCeilingSec)
    }

    @Test
    fun `UNLEASHED is the maximum`() {
        val c = SubAgentDispatchProfileRules.caps(SubAgentDispatchProfile.UNLEASHED)
        assertEquals(64, c.globalCap)
        assertEquals(16, c.perAssistantCap)
        assertEquals(120_000L, c.slotWaitMs)
        assertEquals(60, c.maxTripsCeiling)
        assertEquals(3600, c.timeoutCeilingSec)
    }

    @Test
    fun `ASSIST is conservative`() {
        val c = SubAgentDispatchProfileRules.caps(SubAgentDispatchProfile.ASSIST)
        assertEquals(4, c.globalCap)
        assertEquals(1, c.perAssistantCap)
        assertEquals(10_000L, c.slotWaitMs)
        assertEquals(8, c.maxTripsCeiling)
        assertEquals(300, c.timeoutCeilingSec)
    }

    @Test
    fun `caps are monotonically non-decreasing from ASSIST to UNLEASHED`() {
        val order = listOf(
            SubAgentDispatchProfile.ASSIST,
            SubAgentDispatchProfile.NORMAL,
            SubAgentDispatchProfile.BURST,
            SubAgentDispatchProfile.UNLEASHED,
        )
        for (i in 0 until order.size - 1) {
            val a = SubAgentDispatchProfileRules.caps(order[i])
            val b = SubAgentDispatchProfileRules.caps(order[i + 1])
            assertTrue("globalCap ${order[i]} < ${order[i + 1]}", a.globalCap <= b.globalCap)
            assertTrue("perAssistantCap ${order[i]} < ${order[i + 1]}", a.perAssistantCap <= b.perAssistantCap)
        }
    }

    @Test
    fun `effectivePerAssistantCap returns 0 for NEVER`() {
        assertEquals(0, SubAgentDispatchProfileRules.effectivePerAssistantCap(SubAgentDispatchProfile.NEVER, 5))
    }

    @Test
    fun `effectivePerAssistantCap takes min of profile and assistant`() {
        // NORMAL cap=8, assistant=5 → 5
        assertEquals(5, SubAgentDispatchProfileRules.effectivePerAssistantCap(SubAgentDispatchProfile.NORMAL, 5))
        // NORMAL cap=8, assistant=2 → 2
        assertEquals(2, SubAgentDispatchProfileRules.effectivePerAssistantCap(SubAgentDispatchProfile.NORMAL, 2))
        // BURST cap=8, assistant=16 → 8
        assertEquals(8, SubAgentDispatchProfileRules.effectivePerAssistantCap(SubAgentDispatchProfile.BURST, 16))
    }

    @Test
    fun `clampMaxTrips respects ceiling`() {
        assertEquals(8, SubAgentDispatchProfileRules.clampMaxTrips(SubAgentDispatchProfile.ASSIST, 100))
        assertEquals(60, SubAgentDispatchProfileRules.clampMaxTrips(SubAgentDispatchProfile.NORMAL, 100))
        assertEquals(60, SubAgentDispatchProfileRules.clampMaxTrips(SubAgentDispatchProfile.UNLEASHED, 100))
        assertEquals(5, SubAgentDispatchProfileRules.clampMaxTrips(SubAgentDispatchProfile.NORMAL, 5))
        // 0 = unlimited (inherited from the parent assistant) and must survive the clamp, so
        // ConversationToolStepLimits.clear() applies and the global budget governs the run.
        assertEquals(0, SubAgentDispatchProfileRules.clampMaxTrips(SubAgentDispatchProfile.NORMAL, 0))
    }

    @Test
    fun `clampTimeout respects ceiling`() {
        assertEquals(300, SubAgentDispatchProfileRules.clampTimeout(SubAgentDispatchProfile.ASSIST, 9999))
        assertEquals(1800, SubAgentDispatchProfileRules.clampTimeout(SubAgentDispatchProfile.NORMAL, 9999))
        assertEquals(3600, SubAgentDispatchProfileRules.clampTimeout(SubAgentDispatchProfile.UNLEASHED, 9999))
        assertEquals(100, SubAgentDispatchProfileRules.clampTimeout(SubAgentDispatchProfile.NORMAL, 100))
    }
}
