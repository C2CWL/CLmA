package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentDispatchProfileTest {

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
    fun `NORMAL matches old compiled defaults`() {
        val c = SubAgentDispatchProfileRules.caps(SubAgentDispatchProfile.NORMAL)
        assertEquals(30, c.globalCap)
        assertEquals(3, c.perAssistantCap)
        assertEquals(30_000L, c.slotWaitMs)
        assertEquals(30, c.maxTripsCeiling)
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
        // NORMAL cap=3, assistant=5 → 3
        assertEquals(3, SubAgentDispatchProfileRules.effectivePerAssistantCap(SubAgentDispatchProfile.NORMAL, 5))
        // NORMAL cap=3, assistant=2 → 2
        assertEquals(2, SubAgentDispatchProfileRules.effectivePerAssistantCap(SubAgentDispatchProfile.NORMAL, 2))
        // BURST cap=8, assistant=16 → 8
        assertEquals(8, SubAgentDispatchProfileRules.effectivePerAssistantCap(SubAgentDispatchProfile.BURST, 16))
    }

    @Test
    fun `clampMaxTrips respects ceiling`() {
        assertEquals(8, SubAgentDispatchProfileRules.clampMaxTrips(SubAgentDispatchProfile.ASSIST, 100))
        assertEquals(30, SubAgentDispatchProfileRules.clampMaxTrips(SubAgentDispatchProfile.NORMAL, 100))
        assertEquals(60, SubAgentDispatchProfileRules.clampMaxTrips(SubAgentDispatchProfile.UNLEASHED, 100))
        assertEquals(5, SubAgentDispatchProfileRules.clampMaxTrips(SubAgentDispatchProfile.NORMAL, 5))
    }

    @Test
    fun `clampTimeout respects ceiling`() {
        assertEquals(300, SubAgentDispatchProfileRules.clampTimeout(SubAgentDispatchProfile.ASSIST, 9999))
        assertEquals(1800, SubAgentDispatchProfileRules.clampTimeout(SubAgentDispatchProfile.NORMAL, 9999))
        assertEquals(3600, SubAgentDispatchProfileRules.clampTimeout(SubAgentDispatchProfile.UNLEASHED, 9999))
        assertEquals(100, SubAgentDispatchProfileRules.clampTimeout(SubAgentDispatchProfile.NORMAL, 100))
    }
}
