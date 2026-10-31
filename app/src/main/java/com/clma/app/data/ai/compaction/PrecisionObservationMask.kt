package com.clma.app.data.ai.compaction

import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.ContextCompactionPresentation
import com.clma.app.data.ai.tools.ToolExecutionRetryPolicy
import com.clma.app.data.ai.transformers.InputMessageTransformer
import com.clma.app.data.ai.transformers.TransformerContext

/**
 * Request-only observation mask for the precision-compaction experiment.
 *
 * Stored messages are never rewritten. When [enabled], completed read-only tool results older
 * than the latest [KEEP_RECENT] calls, and longer than [MIN_OUTPUT_CHARS], are replaced in the
 * model request by a short stub. The stub keeps the call id and the original input so the model
 * can run that same read again. Writes, shell, and anything [ToolExecutionRetryPolicy] does not
 * classify as a pure read stay verbatim: re-running them could repeat a side effect.
 */
internal object PrecisionObservationMask {
    const val KEEP_RECENT = 2
    const val MIN_OUTPUT_CHARS = 1_200
    const val MARKER = "[precision observation masked]"

    private const val HEAD_CHARS = 320
    private const val TAIL_CHARS = 160
    private const val INPUT_CHARS = 360

    fun apply(messages: List<UIMessage>, enabled: Boolean): List<UIMessage> {
        if (!enabled) return messages
        val completed = mutableListOf<LocatedTool>()
        messages.forEachIndexed { messageIndex, message ->
            message.parts.forEachIndexed { partIndex, part ->
                if (
                    part is UIMessagePart.Tool &&
                    part.output.isNotEmpty() &&
                    !ContextCompactionPresentation.isDisplayTool(part)
                ) {
                    completed += LocatedTool(messageIndex, partIndex, part)
                }
            }
        }
        if (completed.size <= KEEP_RECENT) return messages

        val replacements = HashMap<Pair<Int, Int>, UIMessagePart.Tool>()
        val oldestKept = completed.size - KEEP_RECENT
        for (index in 0 until oldestKept) {
            val located = completed[index]
            val masked = mask(located.tool) ?: continue
            replacements[located.messageIndex to located.partIndex] = masked
        }
        if (replacements.isEmpty()) return messages

        return messages.mapIndexed { messageIndex, message ->
            if (replacements.keys.none { it.first == messageIndex }) {
                message
            } else {
                message.copy(
                    parts = message.parts.mapIndexed { partIndex, part ->
                        replacements[messageIndex to partIndex] ?: part
                    },
                )
            }
        }
    }

    private fun mask(tool: UIMessagePart.Tool): UIMessagePart.Tool? {
        if (tool.output.any { it !is UIMessagePart.Text }) return null
        if (!ToolExecutionRetryPolicy.isIdempotentReadOnly(tool.toolName, tool.inputAsJson())) return null
        val text = tool.output.joinToString(separator = "") { (it as UIMessagePart.Text).text }
        if (text.length <= MIN_OUTPUT_CHARS || text.contains(MARKER)) return null
        return tool.copy(output = listOf(UIMessagePart.Text(stub(tool, text))))
    }

    private fun stub(tool: UIMessagePart.Tool, text: String): String = buildString {
        appendLine(MARKER)
        append("tool=").appendLine(tool.toolName)
        append("call_id=").appendLine(tool.toolCallId)
        append("chars=").appendLine(text.length)
        append("input=").appendLine(abbreviate(tool.input, INPUT_CHARS))
        appendLine("head:")
        appendLine(takeHead(text, HEAD_CHARS))
        appendLine("tail:")
        appendLine(takeTail(text, TAIL_CHARS))
        append("Full output remains stored on this call. Re-run ")
        append(tool.toolName)
        append(" with the same input to read it again. This stub is not the complete result.")
    }

    private fun abbreviate(text: String, limit: Int): String {
        if (text.length <= limit) return text
        return takeHead(text, limit) + "…[input truncated]"
    }

    private fun takeHead(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var end = limit
        if (end > 0 && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end)
    }

    private fun takeTail(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var start = text.length - limit
        if (start > 0 && text[start].isLowSurrogate()) start++
        return text.substring(start)
    }

    private data class LocatedTool(
        val messageIndex: Int,
        val partIndex: Int,
        val tool: UIMessagePart.Tool,
    )
}

/** Applies [PrecisionObservationMask] only while the experiment switch is on. */
object PrecisionObservationMaskTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = PrecisionObservationMask.apply(
        messages = messages,
        enabled = ctx.settings.enablePrecisionCompaction,
    )
}
