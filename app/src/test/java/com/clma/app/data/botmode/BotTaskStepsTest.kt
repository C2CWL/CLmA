package com.clma.app.data.botmode

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.ToolApprovalState
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.model.Conversation
import com.clma.app.data.model.MessageNode
import com.clma.app.subagent.SubAgentRun
import com.clma.app.subagent.SubAgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BotTaskStepsTest {

    private val assistantId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val conversationId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    @Test
    fun `idle when nothing is running`() {
        val steps = buildBotTaskSteps(
            conversation = emptyConversation(),
            processingStatus = null,
            subAgentRuns = emptyList(),
            isGenerating = false,
            idleLabel = "idle",
        )
        assertEquals(1, steps.size)
        assertEquals(BotTaskStepStatus.PENDING, steps.single().status)
        assertEquals("idle", steps.single().title)
    }

    @Test
    fun `generating without tools shows generate step`() {
        val steps = buildBotTaskSteps(
            conversation = emptyConversation(),
            processingStatus = null,
            subAgentRuns = emptyList(),
            isGenerating = true,
            generatingLabel = "gen",
        )
        assertEquals(BotTaskStepStatus.ACTIVE, steps.single().status)
        assertEquals("gen", steps.single().title)
    }

    @Test
    fun `tool statuses map from execution and approval`() {
        val done = tool("t1", "search", executed = true)
        val pending = tool("t2", "ask", approval = ToolApprovalState.Pending)
        val failed = tool("t3", "rm", approval = ToolApprovalState.Denied("no"))
        val running = tool("t4", "run", started = true)
        val conv = conversationWithAssistantParts(done, pending, failed, running)
        val steps = buildBotTaskSteps(conv, null, emptyList(), isGenerating = true)
        assertEquals(BotTaskStepStatus.DONE, steps[0].status)
        assertEquals(BotTaskStepStatus.PENDING, steps[1].status)
        assertEquals(BotTaskStepStatus.FAILED, steps[2].status)
        assertEquals(BotTaskStepStatus.ACTIVE, steps[3].status)
    }

    @Test
    fun `sub-agent runs for this chat appear as steps`() {
        val run = SubAgentRun(
            id = "r1",
            parentChatId = conversationId.toString(),
            parentAssistantId = assistantId.toString(),
            label = "Research",
            task = "look up",
            modelId = null,
            tools = null,
            runInBackground = true,
            timeoutSeconds = 60,
            maxTrips = 3,
            status = SubAgentStatus.RUNNING,
            startedAtMs = 1L,
        )
        val other = run.copy(id = "r2", parentChatId = "other")
        val steps = buildBotTaskSteps(emptyConversation(), null, listOf(run, other), false)
        assertTrue(steps.any { it.id == "sub:r1" && it.status == BotTaskStepStatus.ACTIVE })
        assertTrue(steps.none { it.id == "sub:r2" })
    }

    private fun emptyConversation() = Conversation.ofId(conversationId, assistantId)

    private fun conversationWithAssistantParts(vararg parts: UIMessagePart): Conversation {
        val user = UIMessage.user("hi")
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts.toList(),
        )
        return Conversation(
            id = conversationId,
            assistantId = assistantId,
            messageNodes = listOf(
                MessageNode.of(user),
                MessageNode.of(assistant),
            ),
        )
    }

    private fun tool(
        id: String,
        name: String,
        executed: Boolean = false,
        approval: ToolApprovalState = ToolApprovalState.Auto,
        started: Boolean = false,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = "{}",
        output = if (executed) listOf(UIMessagePart.Text("ok")) else emptyList(),
        approvalState = approval,
        executionStartedAt = if (started) 1L else null,
    )
}
