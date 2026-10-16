package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure-logic tests for [SkillGroupRules]. No Android dependency — the rules operate on
 * plain [SkillGroup] lists and string sets, so these run under kcheck without a device.
 */
class SkillGroupRulesTest {

    private val g1 = SkillGroup(id = "a", name = "Alpha", skillNames = setOf("s1", "s2"), enabled = true)
    private val g2 = SkillGroup(id = "b", name = "Beta", skillNames = setOf("s3"), enabled = false)
    private val g3 = SkillGroup(id = "c", name = "Gamma", skillNames = setOf("s4", "s5"), enabled = false)
    private val groups = listOf(g1, g2, g3)

    @Test
    fun `disabled skill names come only from disabled groups`() {
        val disabled = SkillGroupRules.disabledSkillNames(groups)
        assertEquals(setOf("s3", "s4", "s5"), disabled)
    }

    @Test
    fun `disabled set is empty when all groups are enabled`() {
        val allOn = listOf(g1, g2.copy(enabled = true), g3.copy(enabled = true))
        assertTrue(SkillGroupRules.disabledSkillNames(allOn).isEmpty())
    }

    @Test
    fun `effective enabled skills subtracts disabled members`() {
        val enabled = setOf("s1", "s2", "s3", "s6")
        val effective = SkillGroupRules.effectiveEnabledSkills(enabled, groups)
        // s3 is in disabled g2; s6 is ungrouped so stays.
        assertEquals(setOf("s1", "s2", "s6"), effective)
    }

    @Test
    fun `core skills cannot be grouped`() {
        assertFalse(SkillGroupRules.canGroupSkill("agent-core"))
        assertFalse(SkillGroupRules.canGroupSkill("autonomous-agent"))
        assertTrue(SkillGroupRules.canGroupSkill("auto-reply"))
        assertTrue(SkillGroupRules.canGroupSkill("my-own-skill"))
    }

    @Test
    fun `assign to group removes from previous group`() {
        // s2 is in g1; assign it to g2 → it leaves g1.
        val updated = SkillGroupRules.assignToGroup(groups, "b", "s2")
        val g1u = updated.first { it.id == "a" }
        val g2u = updated.first { it.id == "b" }
        assertEquals(setOf("s1"), g1u.skillNames)
        assertTrue("s2" in g2u.skillNames)
        assertTrue("s3" in g2u.skillNames)
    }

    @Test
    fun `assign to unknown group is a no-op`() {
        val updated = SkillGroupRules.assignToGroup(groups, "zzz", "s1")
        assertEquals(groups, updated)
    }

    @Test
    fun `remove from group plucks it out`() {
        val updated = SkillGroupRules.removeFromGroup(groups, "s3")
        val g2u = updated.first { it.id == "b" }
        assertTrue("s3" !in g2u.skillNames)
    }

    @Test
    fun `remove a skill that is in no group is a no-op`() {
        val updated = SkillGroupRules.removeFromGroup(groups, "s99")
        assertEquals(groups, updated)
    }

    @Test
    fun `set group enabled toggles only the target`() {
        val off = SkillGroupRules.setGroupEnabled(groups, "a", enabled = false)
        assertFalse(off.first { it.id == "a" }.enabled)          // target flipped to false
        assertFalse(off.first { it.id == "b" }.enabled)          // b was already false, untouched
        assertEquals(setOf("s3"), off.first { it.id == "b" }.skillNames)  // members untouched
        val on = SkillGroupRules.setGroupEnabled(groups, "b", enabled = true)
        assertTrue(on.first { it.id == "b" }.enabled)            // target flipped to true
        assertTrue(on.first { it.id == "a" }.enabled)            // a untouched, still true
    }

    @Test
    fun `rename group changes only the name`() {
        val updated = SkillGroupRules.renameGroup(groups, "a", "Renamed")
        assertEquals("Renamed", updated.first { it.id == "a" }.name)
        assertEquals("Beta", updated.first { it.id == "b" }.name)
    }

    @Test
    fun `delete group removes definition but skills stay`() {
        val updated = SkillGroupRules.deleteGroup(groups, "b")
        assertEquals(2, updated.size)
        // The skills that were in g2 are now ungrouped (not in any remaining group).
        assertTrue("s3" !in SkillGroupRules.groupedSkillNames(updated))
    }

    @Test
    fun `grouped skill names is the union across all groups`() {
        assertEquals(setOf("s1", "s2", "s3", "s4", "s5"), SkillGroupRules.groupedSkillNames(groups))
    }

    @Test
    fun `ungrouped excludes grouped and core skills`() {
        val all = listOf("s1", "s2", "s3", "s6", "s7", "agent-core", "autonomous-agent")
        val core = setOf("agent-core", "autonomous-agent")
        val ungrouped = SkillGroupRules.ungroupedSkillNames(all, groups, core)
        assertEquals(listOf("s6", "s7"), ungrouped)
    }

    @Test
    fun `empty groups yield empty disabled set`() {
        assertTrue(SkillGroupRules.disabledSkillNames(emptyList()).isEmpty())
    }
}
