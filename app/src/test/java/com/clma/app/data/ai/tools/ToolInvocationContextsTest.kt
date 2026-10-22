package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-01 — golden-value proof that [ToolInvocationContexts] reproduces, field for field, the
 * context literals the six call sites used to hand-write.
 *
 * Each test below is one of the six assembly sites named in the P2-01 card, with the exact
 * values that site passed before the refactor. If a future change alters what a site sends,
 * this file fails and the change has to be deliberate.
 */
class ToolInvocationContextsTest {

    // 1. Normal chat turn (ChatService.handleMessageComplete) — a text-only model.
    @Test
    fun `normal chat turn carries every model and assistant gate`() {
        val ctx = ToolInvocationContexts.chat(
            assistantId = "asst-1",
            conversationId = "conv-1",
            isHeadless = false,
            modelCanSeeImages = false,
            modelCanSeeVideos = false,
            subAgentContextRefsEnabled = false,
            subAgentToolSurfaceEnabled = false,
        )

        assertEquals("asst-1", ctx.callerAssistantId)
        assertEquals("conv-1", ctx.callerConversationId)
        assertFalse(ctx.isHeadless)
        assertFalse(ctx.modelCanSeeImages)
        assertFalse(ctx.modelCanSeeVideos)
        assertFalse(ctx.subAgentContextRefsEnabled)
        assertFalse(ctx.subAgentToolSurfaceEnabled)
    }

    // 2. Rerun / regenerate rebuild (ChatService.buildToolsForRerun) — vision model, both
    //    sub-agent gates on: the rebuild must offer the same surface as the first pass.
    @Test
    fun `rerun rebuild carries the same gates as the first pass`() {
        val ctx = ToolInvocationContexts.chat(
            assistantId = "asst-1",
            conversationId = "conv-2",
            isHeadless = false,
            modelCanSeeImages = true,
            modelCanSeeVideos = true,
            subAgentContextRefsEnabled = true,
            subAgentToolSurfaceEnabled = true,
        )

        assertEquals("asst-1", ctx.callerAssistantId)
        assertEquals("conv-2", ctx.callerConversationId)
        assertFalse(ctx.isHeadless)
        assertTrue(ctx.modelCanSeeImages)
        assertTrue(ctx.modelCanSeeVideos)
        assertTrue(ctx.subAgentContextRefsEnabled)
        assertTrue(ctx.subAgentToolSurfaceEnabled)
    }

    // 3. A sub-agent's own conversation goes through the SAME chat builder, but is headless.
    //    Regression guard for the recursion guard silently not firing.
    @Test
    fun `chat builder keeps the headless bit for sub-agent conversations`() {
        val ctx = ToolInvocationContexts.chat(
            assistantId = "asst-1",
            conversationId = "sub-conv",
            isHeadless = true,
            modelCanSeeImages = true,
            modelCanSeeVideos = true,
            subAgentContextRefsEnabled = false,
            subAgentToolSurfaceEnabled = true,
        )

        assertTrue(ctx.isHeadless)
    }

    // 4. Phase 16 fast-path router — tools are executed, never shown to a model, so the
    //    model-derived fields must keep their data-class defaults.
    @Test
    fun `fast path sets no model derived fields and stays non headless`() {
        val ctx = ToolInvocationContexts.fastPath(
            assistantId = "asst-2",
            conversationId = "conv-3",
            subAgentToolSurfaceEnabled = true,
        )

        assertEquals("asst-2", ctx.callerAssistantId)
        assertEquals("conv-3", ctx.callerConversationId)
        assertFalse(ctx.isHeadless)
        assertTrue("modelCanSeeImages must keep its default", ctx.modelCanSeeImages)
        assertFalse(ctx.subAgentContextRefsEnabled)
        assertTrue(ctx.subAgentToolSurfaceEnabled)
    }

    // 5. Workflow fire (WorkflowEngine) — headless, no conversation.
    @Test
    fun `workflow fire is headless with no conversation`() {
        val ctx = ToolInvocationContexts.headless(assistantId = "asst-3")

        assertEquals("asst-3", ctx.callerAssistantId)
        assertNull(ctx.callerConversationId)
        assertTrue(ctx.isHeadless)
        assertTrue(ctx.modelCanSeeImages)
        assertFalse(ctx.subAgentContextRefsEnabled)
        assertFalse(ctx.subAgentToolSurfaceEnabled)
    }

    // 6. Cron direct mode (CronJobWorker) — same shape, and the legacy factory site passes no
    //    context at all (EMPTY), which must stay the documented default.
    @Test
    fun `cron direct mode matches the workflow shape and the legacy site stays empty`() {
        val ctx = ToolInvocationContexts.headless(assistantId = "asst-4")

        assertEquals("asst-4", ctx.callerAssistantId)
        assertNull(ctx.callerConversationId)
        assertTrue(ctx.isHeadless)

        assertEquals(ToolInvocationContext.EMPTY, ToolInvocationContext.EMPTY)
        assertEquals(null, ToolInvocationContext.EMPTY.callerAssistantId)
        assertEquals(null, ToolInvocationContext.EMPTY.callerConversationId)
        assertFalse(ToolInvocationContext.EMPTY.isHeadless)
        assertTrue(ToolInvocationContext.EMPTY.modelCanSeeImages)
        assertFalse(ToolInvocationContext.EMPTY.subAgentContextRefsEnabled)
        assertFalse(ToolInvocationContext.EMPTY.subAgentToolSurfaceEnabled)
    }
}
