package com.clma.app.service

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.tools.ToolApprovalPolicy

/** Shared selection for dialogs and the edge indicator; historical turns never block a new turn. */
object PendingToolInteractions {
    fun pendingTools(messages: List<UIMessage>): List<UIMessagePart.Tool> = messages
        .drop(messages.indexOfLast { it.role == MessageRole.USER } + 1)
        .flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Tool>()
        .filter { it.isPending }

    fun needsInput(tool: UIMessagePart.Tool): Boolean = tool.toolName == ToolApprovalPolicy.ASK_USER_TOOL_NAME ||
        tool.toolName == ToolApprovalPolicy.REQUEST_SECRET_TOOL_NAME
}
