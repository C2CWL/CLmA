package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * P2-01 — the Assistant/Model → context mapping, plus the guarantee that "no context" stays
 * [ToolInvocationContext.EMPTY].
 */
class ToolSurfaceResolverTest {

    private val assistantId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val conversationId = Uuid.parse("66666666-7777-8888-9999-000000000000")

    private fun assistant(
        refs: Boolean = false,
        surface: Boolean = false,
    ) = Assistant(
        id = assistantId,
        name = "resolver-test",
        enableSubAgentContextRefs = refs,
        enableSubAgentToolSurface = surface,
    )

    private fun model(vararg modalities: Modality) =
        Model(modelId = "test/model", displayName = "Test", inputModalities = modalities.toList())

    @Test
    fun `chat context reads every gate off the assistant and the model`() {
        val ctx = ToolSurfaceResolver.chatContext(
            assistant = assistant(refs = true, surface = true),
            conversationId = conversationId,
            model = model(Modality.TEXT, Modality.IMAGE),
            isHeadless = false,
        )

        assertEquals(assistantId.toString(), ctx.callerAssistantId)
        assertEquals(conversationId.toString(), ctx.callerConversationId)
        assertFalse(ctx.isHeadless)
        assertTrue(ctx.modelCanSeeImages)
        assertTrue(ctx.subAgentContextRefsEnabled)
        assertTrue(ctx.subAgentToolSurfaceEnabled)
    }

    @Test
    fun `chat context reports a text-only model as unable to see images`() {
        val ctx = ToolSurfaceResolver.chatContext(
            assistant = assistant(),
            conversationId = conversationId,
            model = model(Modality.TEXT),
            isHeadless = true,
        )

        assertFalse(ctx.modelCanSeeImages)
        assertTrue(ctx.isHeadless)
    }

    @Test
    fun `fast path context mirrors the assistant freeze flag only`() {
        val ctx = ToolSurfaceResolver.fastPathContext(
            assistant = assistant(refs = true, surface = true),
            conversationId = conversationId,
        )

        assertEquals(assistantId.toString(), ctx.callerAssistantId)
        assertEquals(conversationId.toString(), ctx.callerConversationId)
        assertFalse(ctx.isHeadless)
        assertTrue(ctx.modelCanSeeImages)
        assertFalse(ctx.subAgentContextRefsEnabled)
        assertTrue(ctx.subAgentToolSurfaceEnabled)
    }

    @Test
    fun `headless context is headless with no conversation`() {
        val ctx = ToolSurfaceResolver.headlessContext(assistantId)

        assertEquals(assistantId.toString(), ctx.callerAssistantId)
        assertEquals(null, ctx.callerConversationId)
        assertTrue(ctx.isHeadless)
    }

    @Test
    fun `contextless stays the empty context`() {
        assertEquals(ToolInvocationContext.EMPTY, ToolSurfaceResolver.contextless)
        assertEquals(null, ToolSurfaceResolver.contextless.callerAssistantId)
        assertFalse(ToolSurfaceResolver.contextless.isHeadless)
    }
}
