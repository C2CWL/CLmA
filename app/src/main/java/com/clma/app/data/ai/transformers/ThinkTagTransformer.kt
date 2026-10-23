package com.clma.app.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import kotlin.time.Clock
import kotlin.time.Instant

private val THINKING_REGEX = Regex("\\A\\s*<think>([\\s\\S]*?)(</think>|$)")
private val CLOSED_THINKING_REGEX = Regex("\\A\\s*<think>([\\s\\S]*?)</think>")

// 部分供应商不会返回reasoning parts, 或者同时返回reasoning字段与正文<think>标签, 所以需要这个transformer
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.transformThinkTags(
            now = Clock.System.now(),
            generationFinished = false,
        )
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.transformThinkTags(
            now = Clock.System.now(),
            generationFinished = true,
        )
    }
}

internal fun List<UIMessage>.transformThinkTags(
    now: Instant,
    generationFinished: Boolean,
): List<UIMessage> = map { message ->
    if (message.role != MessageRole.ASSISTANT) {
        return@map message
    }
    val existingReasoningIndex = message.parts.indexOfFirst { it is UIMessagePart.Reasoning }
    if (existingReasoningIndex >= 0) {
        val existingReasoning = message.parts[existingReasoningIndex] as UIMessagePart.Reasoning
        val textPartIndex = message.parts.indexOfFirst { part ->
            part is UIMessagePart.Text && part.text.isNotBlank()
        }
        val textPart = message.parts.getOrNull(textPartIndex) as? UIMessagePart.Text
        // When native reasoning already has non-blank text, only strip an explicit closed
        // <think>...</think> prefix so a literal "<think>" mention without closing tag is preserved.
        // When the existing Reasoning part is blank (e.g. empty reasoning_content placeholder),
        // also match unclosed prefix tags during streaming.
        val regex = if (existingReasoning.reasoning.isBlank()) THINKING_REGEX else CLOSED_THINKING_REGEX
        val match = textPart?.let { regex.find(it.text) }
        if (match != null && textPart != null) {
            val extracted = match.groupValues[1].trim()
            val hasClosingTag = regex === CLOSED_THINKING_REGEX || match.groups[2]?.value == "</think>"
            val mergedReasoningText = when {
                existingReasoning.reasoning.isBlank() -> extracted
                extracted.isBlank() || existingReasoning.reasoning.trim() == extracted -> existingReasoning.reasoning
                else -> existingReasoning.reasoning.trimEnd() + "\n\n" + extracted
            }
            val updatedReasoning = existingReasoning.copy(
                reasoning = mergedReasoningText,
                finishedAt = existingReasoning.finishedAt ?: if (generationFinished || hasClosingTag) now else null,
            )
            val strippedText = textPart.copy(text = textPart.text.removeRange(match.range))
            return@map message.copy(
                parts = message.parts.mapIndexed { idx, part ->
                    when {
                        idx == existingReasoningIndex -> updatedReasoning
                        idx == textPartIndex -> strippedText
                        part is UIMessagePart.Reasoning && generationFinished && part.finishedAt == null ->
                            part.copy(finishedAt = now)
                        else -> part
                    }
                }
            )
        }
        return@map if (generationFinished) {
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = now)
                    } else {
                        part
                    }
                }
            )
        } else {
            message
        }
    }

    val textPartIndex = message.parts.indexOfFirst { part ->
        part is UIMessagePart.Text && part.text.isNotBlank()
    }
    val textPart = message.parts.getOrNull(textPartIndex) as? UIMessagePart.Text
        ?: return@map message
    val match = THINKING_REGEX.find(textPart.text) ?: return@map message
    val hasClosingTag = match.groups[2]?.value == "</think>"
    val reasoning = UIMessagePart.Reasoning(
        reasoning = match.groupValues[1].trim(),
        createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
        finishedAt = if (generationFinished || hasClosingTag) now else null,
    )
    val strippedText = textPart.copy(text = textPart.text.removeRange(match.range))

    message.copy(
        parts = buildList {
            addAll(message.parts.subList(0, textPartIndex))
            add(reasoning)
            add(strippedText)
            addAll(message.parts.subList(textPartIndex + 1, message.parts.size))
        }
    )
}
