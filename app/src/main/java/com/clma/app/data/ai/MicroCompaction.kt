package com.clma.app.data.ai

import androidx.collection.LruCache
import com.clma.ai.provider.TOOL_OUTPUT_PROJECTION_MARKER
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Request-only projection adapted from moxw (AGPL-3.0, 81bbb7e). */
internal class MicroCompaction {
    data class Projection(val messages: List<UIMessage>, val newResults: Int)

    private data class Key(val messageId: Uuid, val part: Int, val callId: String)
    private data class Saved(val digest: String, val file: String, val notice: String)
    private data class Slot(val message: Int, val part: Int, val tool: UIMessagePart.Tool, val key: Key)

    // The originals stay in chat history. Keep only digests and references in memory.
    private val saved = LruCache<Key, Saved>(512)

    @Synchronized
    fun project(
        messages: List<UIMessage>,
        inputBudget: Long?,
        isReadable: (String) -> Boolean = { true },
        spill: (String, String) -> String?,
    ): Projection {
        val slots = buildList {
            messages.forEachIndexed { m, message ->
                message.parts.forEachIndexed { p, part ->
                    if (part is UIMessagePart.Tool && part.isExecuted) {
                        add(Slot(m, p, part, Key(message.id, p, part.toolCallId)))
                    }
                }
            }
        }.dropLast(KEEP_RECENT)
        if (slots.isEmpty()) return Projection(messages, 0)

        val replacements = mutableMapOf<Int, MutableMap<Int, UIMessagePart.Tool>>()
        fun replace(slot: Slot, notice: String) {
            replacements.getOrPut(slot.message) { mutableMapOf() }[slot.part] =
                slot.tool.copy(output = listOf(UIMessagePart.Text(notice)))
        }
        fun applyReplacements(): List<UIMessage> = if (replacements.isEmpty()) messages else
            messages.mapIndexed { m, message ->
                val parts = replacements[m] ?: return@mapIndexed message
                message.copy(parts = message.parts.mapIndexed { p, part -> parts[p] ?: part })
            }

        val unprojected = slots.filter { slot ->
            val previous = saved[slot.key]
            val text = if (previous != null) eligibleText(slot.tool) else null
            if (previous != null && text != null && digest(text) == previous.digest && isReadable(previous.file)) {
                replace(slot, previous.notice)
                false
            } else true
        }
        val projected = applyReplacements()
        if (inputBudget == null || unprojected.isEmpty() ||
            ContextBudgetPlanner.estimateContextTokens(projected).toLong() < inputBudget
        ) return Projection(projected, 0)

        // Rewrite eligible history as one batch so that each result leaving the
        // recent tail does not independently invalidate the prefix.
        var newResults = 0
        for (slot in unprojected) {
            val text = eligibleText(slot.tool) ?: continue
            val tokens = ContextBudgetPlanner.estimateTextTokens(text)
            if (tokens < MIN_RESULT_TOKENS) continue
            val digest = digest(text)
            val file = spill("micro-$digest", text) ?: continue
            val notice = "$TOOL_OUTPUT_PROJECTION_MARKER Older result (~$tokens tokens). " +
                "Read its saved text with tool_output_read(file = \"$file\", offset = 1, limit = 200)."
            saved.put(slot.key, Saved(digest, file, notice))
            replace(slot, notice)
            newResults++
        }
        return Projection(if (newResults == 0) projected else applyReplacements(), newResults)
    }

    private fun eligibleText(tool: UIMessagePart.Tool): String? {
        if (tool.toolName == "tool_output_read") return null
        val parts = tool.output.filterIsInstance<UIMessagePart.Text>()
        if (parts.size != tool.output.size || parts.any {
            "\"error\"" in it.text || it.text.startsWith(TOOL_OUTPUT_PROJECTION_MARKER) || "Full output saved to:" in it.text
        }) return null
        return parts.joinToString("\n") { it.text }
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .take(24).joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEEP_RECENT = 6
        private const val MIN_RESULT_TOKENS = 600L

        // Leave room for tool schemas and estimation error, as well as an explicit
        // output allowance when configured. Unknown windows must not trigger a batch.
        fun inputBudget(contextLength: Int?, maxOutputTokens: Int?): Long? = contextLength
            ?.takeIf { it > 0 }
            ?.toLong()
            ?.let { minOf(it * 80 / 100, it - (maxOutputTokens ?: 0).coerceAtLeast(0)).coerceAtLeast(1) }
    }
}
