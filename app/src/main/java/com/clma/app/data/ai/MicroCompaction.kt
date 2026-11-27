package com.clma.app.data.ai

import com.clma.ai.provider.TOOL_OUTPUT_PROJECTION_MARKER
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import java.security.MessageDigest

/** Request-only projection adapted from moxw (AGPL-3.0, 81bbb7e). */
internal object MicroCompaction {
    private const val KEEP_RECENT = 6
    private const val MIN_RESULT_TOKENS = 600L
    private const val TRIGGER_TOKENS = 16_000L

    private data class Slot(val message: Int, val part: Int, val tool: UIMessagePart.Tool, val tokens: Long)

    fun project(messages: List<UIMessage>, spill: (String, String) -> String?): List<UIMessage> {
        val slots = buildList {
            messages.forEachIndexed { m, message ->
                message.parts.forEachIndexed { p, part ->
                    if (part is UIMessagePart.Tool && part.isExecuted) {
                        val tokens = part.output.filterIsInstance<UIMessagePart.Text>()
                            .sumOf { ContextBudgetPlanner.estimateTextTokens(it.text) }
                        add(Slot(m, p, part, tokens))
                    }
                }
            }
        }
        if (slots.size <= KEEP_RECENT || slots.sumOf { it.tokens } < TRIGGER_TOKENS) return messages

        val replacements = mutableMapOf<Int, MutableMap<Int, UIMessagePart.Tool>>()
        for ((m, p, tool, tokens) in slots.dropLast(KEEP_RECENT)) {
            if (tokens < MIN_RESULT_TOKENS || tool.toolName == "tool_output_read") continue
            val textParts = tool.output.filterIsInstance<UIMessagePart.Text>()
            if (textParts.size != tool.output.size || textParts.any {
                "\"error\"" in it.text || it.text.startsWith(TOOL_OUTPUT_PROJECTION_MARKER) || "Full output saved to:" in it.text
            }) continue
            val text = textParts.joinToString("\n") { it.text }
            // Use a separate content-addressed name so a reused provider call ID cannot
            // overwrite a previous spill or point a notice at another tool's output.
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .take(24).joinToString("") { "%02x".format(it) }
            val file = spill("micro-$digest", text) ?: continue
            val notice = "$TOOL_OUTPUT_PROJECTION_MARKER Older result (~$tokens tokens). " +
                "Read its saved text with tool_output_read(file = \"$file\", offset = 1, limit = 200)."
            replacements.getOrPut(m) { mutableMapOf() }[p] = tool.copy(output = listOf(UIMessagePart.Text(notice)))
        }
        if (replacements.isEmpty()) return messages
        return messages.mapIndexed { m, message ->
            val parts = replacements[m] ?: return@mapIndexed message
            message.copy(parts = message.parts.mapIndexed { p, part -> parts[p] ?: part })
        }
    }
}
