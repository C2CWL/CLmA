package com.clma.app.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.tools.ToolApprovalPolicy

/**
 * 思考步骤类型，用于分组 Reasoning、客户端 Tool、ServerTool，以及（可选）过程旁白 Text。
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

    /**
     * 夹在工具/思考轮次之间的过程旁白。
     *
     * 只在 [groupMessageParts] 开启 [foldProcessNarration] 时产生：后面还跟有
     * Reasoning/Tool/ServerTool 的普通 Text 不再拆成正文气泡，而是并进同一条思考链，
     * 避免「再显示 N 步」卡片和旁白正文来回穿插。
     *
     * [index] 是该 Text 在原始 parts 里的下标，给 Compose key 用。
     */
    data class NarrationStep(
        val text: UIMessagePart.Text,
        val index: Int,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock

    /**
     * ask_user / request_secret。正文级卡片，不进思考链，避免表单被「再显示 N 步」折起来。
     *
     * [index] 是该 Tool 在原始 parts 里的下标，给 Compose key 用。
     */
    data class InteractiveToolBlock(
        val tool: UIMessagePart.Tool,
        val index: Int,
    ) : MessagePartBlock
}

/** 向用户要输入的工具：独立卡片，不当成思考步骤。 */
fun UIMessagePart.Tool.isInteractiveInputTool(): Boolean =
    toolName == ToolApprovalPolicy.ASK_USER_TOOL_NAME ||
        toolName == ToolApprovalPolicy.REQUEST_SECRET_TOOL_NAME

/**
 * 将 parts 分组成 ThinkingBlock、InteractiveToolBlock 和 ContentBlock。
 *
 * 连续的 Reasoning、客户端 Tool 和 ServerTool 会被分组到一个 ThinkingBlock 中；
 * 空白 Text 会被跳过，不会把一组步骤切开。
 * ask_user / request_secret 会先 flush 当前思考链，再单独成块。
 *
 * @param foldProcessNarration 为 true 时，若某段非空 Text 后面还跟有思考/工具 part，
 * 则把它收成 [ThinkingStep.NarrationStep] 留在当前思考链里，而不是 flush 成正文。
 * 最后一轮思考/工具之后的 Text（真正回答）仍是 ContentBlock。交互卡片不算思考步骤，
 * 所以它前面的说明文字不会仅仅因为它而被折进思考链。带 `rikkahub.webview`
 * 的 Text 始终当正文卡片，不并进思考链。
 */
fun List<UIMessagePart>.groupMessageParts(
    foldProcessNarration: Boolean = false,
): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    // 开启折叠旁白时，预先找到「最后一次思考/工具」的下标。
    // 交互卡片不算：否则它前面的说明文字会被折进思考链，卡片和旁白对不上。
    val lastThinkingIndex = if (foldProcessNarration) {
        indices.lastOrNull { idx ->
            when (val part = this[idx]) {
                is UIMessagePart.Reasoning,
                is UIMessagePart.ServerTool,
                -> true
                is UIMessagePart.Tool -> !part.isInteractiveInputTool()
                else -> false
            }
        } ?: -1
    } else {
        -1
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                if (part.isInteractiveInputTool()) {
                    flushThinkingSteps()
                    result.add(MessagePartBlock.InteractiveToolBlock(part, index))
                } else {
                    currentThinkingSteps.add(ThinkingStep.ToolStep(part))
                }
            }

            is UIMessagePart.ServerTool -> {
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
            }

            else -> {
                // 空白 Text 不渲染，也不打断当前这组思考步骤（见 isVisuallyBlank）
                if (part is UIMessagePart.Text && part.isVisuallyBlank()) return@fastForEachIndexed

                val foldAsNarration = foldProcessNarration &&
                    part is UIMessagePart.Text &&
                    index < lastThinkingIndex &&
                    part.metadata?.containsKey("rikkahub.webview") != true &&
                    // 插话卡片（合并渲染时用户插话转成的标记 Text）必须保持独立块，
                    // 否则会被折进后半的思考链里，卡片就没了。
                    part.metadata?.containsKey("rikkahub.interjection") != true

                if (foldAsNarration) {
                    currentThinkingSteps.add(
                        ThinkingStep.NarrationStep(text = part, index = index),
                    )
                } else {
                    flushThinkingSteps()
                    result.add(MessagePartBlock.ContentBlock(part, index))
                }
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
 * 它后面一旦出现推理 part、正文 part，或提问/安全输入卡片（下一次思考或输出已经开始），就收起。
 * 后面如果只是另一个普通工具，先不收。非 shell 工具恒为 false。
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
        part is UIMessagePart.Reasoning ||
            part is UIMessagePart.Text ||
            (part is UIMessagePart.Tool && part.isInteractiveInputTool())
    }
}
