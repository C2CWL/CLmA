package com.clma.app.subagent

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart

/** A completed generation is not a successful run unless it produced reportable text. */
internal fun subAgentCompletionError(text: String, failureDetail: String?, retries: Int): String? = when {
    failureDetail != null -> "$failureDetail (after $retries retry attempt(s))"
    text.isBlank() -> "Sub-agent ended without a text result; work is unverified. " +
        "The generation may have reached its step cap or stopped before a final report."
    else -> null
}

/** Intermediate tool-call narration is partial evidence, not a final report. */
internal fun subAgentResultText(messages: List<UIMessage>, allowPartial: Boolean = false): String {
    val assistants = messages.filter { it.role == MessageRole.ASSISTANT }
    val last = assistants.lastOrNull() ?: return ""
    // The pipeline merges successive tool rounds and the closing text into one message.
    val lastToolIndex = last.parts.indexOfLast { it is UIMessagePart.Tool }
    val finalText = last.parts.drop(lastToolIndex + 1).filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }.trim()
    if (finalText.isNotBlank()) return finalText
    if (!allowPartial) return ""
    return assistants.flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
        .joinToString("\n") { it.text }.trim()
}
