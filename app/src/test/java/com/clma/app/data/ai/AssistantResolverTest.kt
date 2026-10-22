package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * P2-01 — pins the four resolution rules the whole app now shares, including which fallback
 * fires and *why* (reported through [AssistantResolver.Source]).
 */
class AssistantResolverTest {

    private val a = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")
    private val b = Uuid.parse("bbbbbbbb-0000-0000-0000-000000000002")
    private val ghost = Uuid.parse("cccccccc-0000-0000-0000-000000000003")

    private val assistantA = Assistant(id = a, name = "A")
    private val assistantB = Assistant(id = b, name = "B")
    private val assistants = listOf(assistantA, assistantB)

    @Test
    fun `byId resolves an existing assistant and returns null otherwise`() {
        assertSame(assistantA, AssistantResolver.byId(assistants, a))
        assertNull(AssistantResolver.byId(assistants, ghost))
        assertNull(AssistantResolver.byId(assistants, null))
    }

    @Test
    fun `current follows the pointer and falls back to the first assistant`() {
        assertSame(assistantB, AssistantResolver.current(assistants, b))
        // Unknown pointer and no pointer at all both fall back to the first assistant.
        assertSame(assistantA, AssistantResolver.current(assistants, ghost))
        assertSame(assistantA, AssistantResolver.current(assistants, null))
    }

    @Test
    fun `forConversation reports whether the fallback fired`() {
        val hit = AssistantResolver.forConversation(assistants, a, b)
        assertSame(assistantA, hit.assistant)
        assertEquals(AssistantResolver.Source.REQUESTED, hit.source)

        val missed = AssistantResolver.forConversation(assistants, ghost, b)
        assertSame(assistantB, missed.assistant)
        assertEquals(AssistantResolver.Source.GLOBAL_CURRENT, missed.source)
    }

    @Test
    fun `forWorkflow prefers the persisted authoring assistant`() {
        val workflows = Assistant(id = ghost, name = "W", localTools = listOf(LocalToolOption.Workflows))
        val pool = assistants + workflows

        val author = AssistantResolver.forWorkflow(pool, b.toString())
        assertSame(assistantB, author?.assistant)
        assertEquals(AssistantResolver.Source.WORKFLOW_AUTHOR, author?.source)
    }

    @Test
    fun `forWorkflow falls back to the first assistant with the Workflows tool`() {
        val workflows = Assistant(id = ghost, name = "W", localTools = listOf(LocalToolOption.Workflows))
        val pool = assistants + workflows

        // Legacy definition (null authoring id) and deleted authoring assistant behave the same.
        val legacy = AssistantResolver.forWorkflow(pool, null)
        assertSame(workflows, legacy?.assistant)
        assertEquals(AssistantResolver.Source.WORKFLOW_TOGGLE_FALLBACK, legacy?.source)

        val deleted = AssistantResolver.forWorkflow(pool, "deadbeef-0000-0000-0000-000000000009")
        assertSame(workflows, deleted?.assistant)
        assertEquals(AssistantResolver.Source.WORKFLOW_TOGGLE_FALLBACK, deleted?.source)
    }

    @Test
    fun `forWorkflow returns null when nothing can run it`() {
        assertNull(AssistantResolver.forWorkflow(assistants, null))
        assertNull(AssistantResolver.forWorkflow(emptyList(), b.toString()))
    }
}
