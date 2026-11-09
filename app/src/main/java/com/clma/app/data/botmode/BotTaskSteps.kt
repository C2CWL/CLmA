package com.clma.app.data.botmode

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.ServerToolStatus
import com.clma.ai.ui.ToolApprovalState
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.model.Conversation
import com.clma.app.subagent.SubAgentRun
import com.clma.app.subagent.SubAgentStatus

/**
 * Phone-side stand-in for OpenMausBot's cloud computer: steps built from the current
 * conversation's tool calls / server tools / sub-agent runs (not a remote desktop).
 */
enum class BotTaskStepStatus {
    DONE,
    ACTIVE,
    PENDING,
    FAILED,
}

data class BotTaskStep(
    val id: String,
    val title: String,
    val status: BotTaskStepStatus,
)

/**
 * Build progress steps from the latest user→assistant turn in [conversation], plus any
 * live sub-agent runs for that chat. Pure so unit tests can cover it without Compose.
 */
fun buildBotTaskSteps(
    conversation: Conversation,
    processingStatus: String?,
    subAgentRuns: Collection<SubAgentRun>,
    isGenerating: Boolean,
    generatingLabel: String = "生成回复",
    idleLabel: String = "暂无进行中的任务",
): List<BotTaskStep> {
    val steps = mutableListOf<BotTaskStep>()
    val turnParts = partsSinceLastUser(conversation)

    turnParts.filterIsInstance<UIMessagePart.Tool>().forEach { tool ->
        steps += BotTaskStep(
            id = "tool:${tool.toolCallId}",
            title = tool.toolName.ifBlank { "tool" },
            status = toolStepStatus(tool, isGenerating),
        )
    }
    turnParts.filterIsInstance<UIMessagePart.ServerTool>().forEach { tool ->
        steps += BotTaskStep(
            id = "server:${tool.toolCallId}",
            title = tool.toolName.ifBlank { "server_tool" },
            status = when (tool.status) {
                ServerToolStatus.COMPLETED -> BotTaskStepStatus.DONE
                ServerToolStatus.FAILED -> BotTaskStepStatus.FAILED
                ServerToolStatus.IN_PROGRESS -> BotTaskStepStatus.ACTIVE
            },
        )
    }

    val chatId = conversation.id.toString()
    subAgentRuns
        .filter { it.parentChatId == chatId }
        .sortedBy { it.startedAtMs }
        .forEach { run ->
            steps += BotTaskStep(
                id = "sub:${run.id}",
                title = run.label.ifBlank { run.task.take(40).ifBlank { "sub-agent" } },
                status = when (run.status) {
                    SubAgentStatus.SUCCEEDED -> BotTaskStepStatus.DONE
                    SubAgentStatus.FAILED, SubAgentStatus.TIMED_OUT, SubAgentStatus.CANCELLED ->
                        BotTaskStepStatus.FAILED
                    SubAgentStatus.RUNNING, SubAgentStatus.PENDING -> BotTaskStepStatus.ACTIVE
                },
            )
        }

    if (processingStatus != null && processingStatus.isNotBlank()) {
        val already = steps.any { it.status == BotTaskStepStatus.ACTIVE && it.title == processingStatus }
        if (!already) {
            steps += BotTaskStep(
                id = "status:processing",
                title = processingStatus,
                status = if (isGenerating) BotTaskStepStatus.ACTIVE else BotTaskStepStatus.DONE,
            )
        }
    }

    if (steps.isEmpty()) {
        if (isGenerating) {
            steps += BotTaskStep("gen:reply", generatingLabel, BotTaskStepStatus.ACTIVE)
        } else {
            steps += BotTaskStep("idle", idleLabel, BotTaskStepStatus.PENDING)
        }
    } else if (isGenerating && steps.none { it.status == BotTaskStepStatus.ACTIVE }) {
        // Model is still streaming after tools finished — show a trailing generate step.
        steps += BotTaskStep("gen:reply", generatingLabel, BotTaskStepStatus.ACTIVE)
    }

    return steps
}

private fun partsSinceLastUser(conversation: Conversation): List<UIMessagePart> {
    val messages = conversation.currentMessages
    if (messages.isEmpty()) return emptyList()
    var start = 0
    for (i in messages.indices.reversed()) {
        if (messages[i].role == MessageRole.USER) {
            start = i + 1
            break
        }
    }
    return messages.drop(start).flatMap { it.parts }
}

private fun toolStepStatus(tool: UIMessagePart.Tool, isGenerating: Boolean): BotTaskStepStatus {
    if (tool.approvalState is ToolApprovalState.Denied) return BotTaskStepStatus.FAILED
    if (tool.isExecuted) return BotTaskStepStatus.DONE
    if (tool.isPending) return BotTaskStepStatus.PENDING
    if (tool.executionStartedAt != null || isGenerating) return BotTaskStepStatus.ACTIVE
    return BotTaskStepStatus.PENDING
}

fun BotTaskStepStatus.toDoneActiveFlags(): Pair<Boolean, Boolean> = when (this) {
    BotTaskStepStatus.DONE -> true to false
    BotTaskStepStatus.ACTIVE -> false to true
    BotTaskStepStatus.PENDING, BotTaskStepStatus.FAILED -> false to false
}
