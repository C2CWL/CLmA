package com.clma.app.service.chatbox

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart

/** One row of the edge-bar chat box's message list. */
sealed interface ChatBoxItem {
    /** A message bubble. [images] / [files] are attachment counts, shown as placeholders. */
    data class Bubble(
        val fromUser: Boolean,
        val text: String,
        val images: Int = 0,
        val files: Int = 0,
    ) : ChatBoxItem

    /** A tool call chip: "✓ 已调用工具：XX" when [done], else "… 正在调用工具：XX". */
    data class ToolChip(val rawName: String, val done: Boolean) : ChatBoxItem
}

object ChatBoxItems {
    /** How many rows the box keeps (the newest ones). */
    const val MAX_ITEMS = 30

    /** Longest text a single bubble shows; longer text keeps its end (the newest part). */
    const val MAX_BUBBLE_CHARS = 4000

    /**
     * Turns a conversation's current messages into compact rows: user / assistant text as
     * bubbles (reasoning is skipped), each tool call as a chip in the order it happened, and
     * images / files as counts. System / tool messages are not shown. Only the newest
     * [MAX_ITEMS] rows are kept.
     */
    fun build(messages: List<UIMessage>): List<ChatBoxItem> {
        val out = ArrayList<ChatBoxItem>()
        for (message in messages) {
            val fromUser = when (message.role) {
                MessageRole.USER -> true
                MessageRole.ASSISTANT -> false
                else -> continue
            }
            val text = StringBuilder()
            var images = 0
            var files = 0
            fun flush() {
                val t = text.toString().trim()
                if (t.isNotEmpty() || images > 0 || files > 0) {
                    out += ChatBoxItem.Bubble(fromUser, clip(t), images, files)
                }
                text.setLength(0)
                images = 0
                files = 0
            }
            for (part in message.parts) {
                when (part) {
                    is UIMessagePart.Text -> {
                        if (text.isNotEmpty() && part.text.isNotBlank()) text.append('\n')
                        text.append(part.text)
                    }
                    is UIMessagePart.Image, is UIMessagePart.Video -> images++
                    is UIMessagePart.Document, is UIMessagePart.Audio -> files++
                    is UIMessagePart.Tool -> {
                        flush()
                        out += ChatBoxItem.ToolChip(part.toolName, part.isExecuted)
                    }
                    else -> Unit
                }
            }
            flush()
        }
        return if (out.size <= MAX_ITEMS) out else out.subList(out.size - MAX_ITEMS, out.size).toList()
    }

    private fun clip(text: String): String =
        if (text.length <= MAX_BUBBLE_CHARS) text else "…" + text.takeLast(MAX_BUBBLE_CHARS)
}
