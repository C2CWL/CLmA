package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-23 (D7) — the folder-filing policy: where a sub-agent conversation goes, and when that folder
 * becomes undeletable. Pure, so it runs in the local harness as well as CI.
 */
class SubAgentArchiveRulesTest {

    private val assistant = "11111111-1111-1111-1111-111111111111"
    private val folder = "22222222-2222-2222-2222-222222222222"

    @Test
    fun `a configured target resolves to its folder`() {
        assertEquals(
            folder,
            SubAgentArchiveRules.targetFolderId(mapOf(assistant to folder), assistant),
        )
    }

    @Test
    fun `no entry means do not file`() {
        assertNull(SubAgentArchiveRules.targetFolderId(emptyMap(), assistant))
        assertNull(SubAgentArchiveRules.targetFolderId(mapOf("other" to folder), assistant))
    }

    @Test
    fun `a malformed stored value reads as do not file`() {
        assertNull(SubAgentArchiveRules.targetFolderId(mapOf(assistant to "not-a-uuid"), assistant))
        assertNull(SubAgentArchiveRules.targetFolderId(mapOf(assistant to ""), assistant))
    }

    @Test
    fun `the archive folder is protected only once it holds a conversation`() {
        assertTrue(SubAgentArchiveRules.isProtected(isArchiveTarget = true, conversationCount = 1))
        assertFalse(SubAgentArchiveRules.isProtected(isArchiveTarget = true, conversationCount = 0))
    }

    @Test
    fun `a non-target folder is never protected`() {
        assertFalse(SubAgentArchiveRules.isProtected(isArchiveTarget = false, conversationCount = 5))
    }
}
