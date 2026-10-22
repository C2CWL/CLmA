package com.clma.app.service.taskcard

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart

/** What the assistant is doing right now, as shown by the edge-bar task card. */
sealed interface TaskCardPhase {
    data object Thinking : TaskCardPhase
    data object Writing : TaskCardPhase
    /** [rawName] is the tool id as the model called it; the view maps it to a display name. */
    data class Tool(val rawName: String) : TaskCardPhase
}

/**
 * A snapshot of one running conversation, derived from its current message list.
 *
 * [step] counts model steps in the current turn: every tool call that already returned closes
 * one step, so a turn that is on its third tool call (or writing after two) is on step 3.
 */
data class TaskCardStatus(
    val phase: TaskCardPhase,
    val step: Int,
    val preview: String,
)

object TaskCardStatusResolver {

    private const val PREVIEW_CHARS = 160

    /**
     * Mirrors ChatService.determineNotificationContent: an unfinished tool call wins, then an
     * open reasoning block, then the reply text. Only the current turn (everything after the
     * last user message) is looked at.
     */
    fun resolve(messages: List<UIMessage>): TaskCardStatus {
        val lastUser = messages.indexOfLast { it.role == MessageRole.USER }
        val turn = if (lastUser >= 0) messages.subList(lastUser + 1, messages.size) else messages
        val parts = turn.flatMap { it.parts }
        val tools = parts.filterIsInstance<UIMessagePart.Tool>()
        val step = tools.count { it.isExecuted } + 1

        val lastParts = turn.lastOrNull()?.parts.orEmpty()
        val lastTool = lastParts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastReasoning = lastParts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastText = lastParts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            lastTool != null && !lastTool.isExecuted -> TaskCardStatus(
                phase = TaskCardPhase.Tool(lastTool.toolName),
                step = step,
                preview = lastText?.text?.let(::tail).orEmpty(),
            )
            lastReasoning != null && lastReasoning.finishedAt == null -> TaskCardStatus(
                phase = TaskCardPhase.Thinking,
                step = step,
                preview = tail(lastReasoning.reasoning),
            )
            lastText != null && lastText.text.isNotBlank() -> TaskCardStatus(
                phase = TaskCardPhase.Writing,
                step = step,
                preview = tail(lastText.text),
            )
            else -> TaskCardStatus(phase = TaskCardPhase.Thinking, step = step, preview = "")
        }
    }

    /** `mcp__<server>__<tool>` → `<tool>`; any other name is returned unchanged. */
    fun displayToolName(raw: String): String {
        val withoutPrefix = raw.removePrefix("mcp__")
        return withoutPrefix.substringAfter("__", missingDelimiterValue = withoutPrefix)
    }

    private fun tail(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= PREVIEW_CHARS) flat else "…" + flat.takeLast(PREVIEW_CHARS)
    }

    /** `m:ss` (or `h:mm:ss` past an hour) for an elapsed duration in milliseconds. */
    fun formatElapsed(elapsedMs: Long): String {
        val total = (elapsedMs.coerceAtLeast(0L) / 1000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%d:%02d".format(m, s)
        }
    }
}
