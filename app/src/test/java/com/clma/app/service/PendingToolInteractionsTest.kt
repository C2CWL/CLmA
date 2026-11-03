package com.clma.app.service

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.ToolApprovalState
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.tools.ToolApprovalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingToolInteractionsTest {
    private fun tool(name: String, state: ToolApprovalState = ToolApprovalState.Pending) =
        UIMessagePart.Tool(toolCallId = name, toolName = name, input = "{}", approvalState = state)

    @Test
    fun `only pending tools in the current turn are selected`() {
        val current = tool("ssh_exec_saved")
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("old"))),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("next"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                current,
                tool("approved", ToolApprovalState.Approved),
                tool("answered", ToolApprovalState.Answered("ok")),
                tool("denied", ToolApprovalState.Denied()),
                tool("auto", ToolApprovalState.Auto),
                tool("executed").copy(output = listOf(UIMessagePart.Text("ok"))),
            )),
        )
        assertEquals(listOf(current), PendingToolInteractions.pendingTools(messages))
        assertTrue(PendingToolInteractions.pendingTools(emptyList()).isEmpty())
    }

    @Test
    fun `pending interaction works without a preceding user message`() {
        val pending = tool(ToolApprovalPolicy.ASK_USER_TOOL_NAME)
        assertEquals(listOf(pending), PendingToolInteractions.pendingTools(listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)),
        )))
    }

    @Test
    fun `questions and secrets require input rather than approval`() {
        assertTrue(PendingToolInteractions.needsInput(tool(ToolApprovalPolicy.ASK_USER_TOOL_NAME)))
        assertTrue(PendingToolInteractions.needsInput(tool(ToolApprovalPolicy.REQUEST_SECRET_TOOL_NAME)))
        assertFalse(PendingToolInteractions.needsInput(tool("ssh_exec_saved")))
    }
}
