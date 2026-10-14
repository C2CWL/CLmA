package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessagePart

/**
 * 思考步骤类型，用于分组 Reasoning、客户端 Tool 和 ServerTool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    data class ServerToolStep(
        val tool: UIMessagePart.ServerTool,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning、客户端 Tool 和 ServerTool 会被分组到一个 ThinkingBlock 中
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                currentThinkingSteps.add(ThinkingStep.ToolStep(part))
            }

            is UIMessagePart.ServerTool -> {
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}

/**
 * workspace_shell 的内联输出要不要收起。
 *
 * 这个调用还是消息里的最后一步时保持展开，输出长出来的过程还在。
 * 它后面一旦出现推理 part 或正文 part（下一次思考或输出已经开始），就收起。
 * 后面如果只是另一个工具，先不收。非 shell 工具恒为 false。
 */
fun shellInlineOutputShouldCollapse(parts: List<UIMessagePart>, tool: UIMessagePart.Tool): Boolean {
    if (tool.toolName != "workspace_shell") return false
    val index = if (tool.toolCallId.isNotBlank()) {
        parts.indexOfFirst { it is UIMessagePart.Tool && it.toolCallId == tool.toolCallId }
    } else {
        parts.indexOfFirst { it === tool }
    }
    if (index < 0) return false
    return parts.drop(index + 1).any { part ->
        part is UIMessagePart.Reasoning || part is UIMessagePart.Text
    }
}
