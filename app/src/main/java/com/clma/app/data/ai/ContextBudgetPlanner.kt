package com.clma.app.data.ai

import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.document.DocumentSizeEstimator

data class ContextBudgetPlan(
    val estimatedInputTokens: Int,
    val triggerTokens: Int,
    val shouldCompact: Boolean,
)

/**
 * Conservative provider-independent context estimator.
 *
 * The most recent real usage is preferred because it includes provider-specific system and
 * tool-schema overhead. Messages added after that response are estimated locally. Providers
 * that do not report usage fall back to estimating the complete message list.
 */
/** Prefix of the text part DocumentAsPromptTransformer builds for each inlined document. */
const val INLINED_DOCUMENT_TAG = "<UploadFile name="

/**
 * Size (estimated tokens) of a document in the prompt, keyed by the document URL. Lets
 * [ContextBudgetPlanner] count a document by its real text instead of a fixed guess.
 *
 * - [record]: what DocumentAsPromptTransformer actually inlined in the most recent request
 *   (already clamped to the inline budget). Always wins.
 * - [estimate]: when nothing was recorded in this process (e.g. after an app restart), estimate
 *   from disk via [DocumentSizeEstimator] — the extracted-text cache if present, otherwise a
 *   file-size heuristic — and memoize it, so each document costs at most one small disk lookup.
 *   This disk estimate is the full document size, not clamped to the inline budget (the planner
 *   does not know the budget here), which errs on the safe side.
 */
object DocumentTokenEstimates {
    private val recordedByUrl = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val diskByUrl = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun record(url: String, tokens: Long) {
        recordedByUrl[url] = tokens.coerceAtLeast(0L)
    }

    /** Recorded size only (no disk access). */
    fun get(url: String): Long? = recordedByUrl[url]

    /** Recorded size, else a memoized disk estimate, else null (not a local file). */
    fun estimate(document: UIMessagePart.Document): Long? {
        recordedByUrl[document.url]?.let { return it }
        diskByUrl[document.url]?.let { return it }
        val fromDisk = DocumentSizeEstimator.estimate(document.url, document.fileName, document.mime)
            ?: return null
        diskByUrl[document.url] = fromDisk
        return fromDisk
    }

    internal fun clear() {
        recordedByUrl.clear()
        diskByUrl.clear()
    }
}

object ContextBudgetPlanner {
    private const val CHARS_PER_TOKEN = 3
    private const val MESSAGE_OVERHEAD_TOKENS = 8
    private const val MEDIA_PART_TOKENS = 1_024

    fun plan(
        messages: List<UIMessage>,
        contextLength: Int,
        thresholdPercent: Int,
        thresholdTokensK: Int? = null,
        reservedTokens: Int = 0,
    ): ContextBudgetPlan {
        require(contextLength > 0) { "contextLength must be positive" }
        val normalizedThreshold = thresholdPercent.coerceIn(5, 95)
        val safeInputBudget = (contextLength.toLong() - reservedTokens.coerceAtLeast(0).toLong())
            .coerceAtLeast(1L)
        val configuredTriggerTokens = thresholdTokensK?.let { tokensK ->
            (tokensK.toLong().coerceAtLeast(1L) * 1_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } ?: (contextLength.toLong() * normalizedThreshold / 100L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val triggerTokens = minOf(configuredTriggerTokens.toLong(), safeInputBudget)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val estimatedInputTokens = estimateInputTokens(messages)
        return ContextBudgetPlan(
            estimatedInputTokens = estimatedInputTokens,
            triggerTokens = triggerTokens,
            shouldCompact = estimatedInputTokens >= triggerTokens,
        )
    }

    fun estimateInputTokens(messages: List<UIMessage>): Int {
        val usageIndex = messages.indexOfLast { message ->
            val usage = message.usage
            usage != null && (usage.totalTokens > 0 || usage.promptTokens + usage.completionTokens > 0)
        }
        val estimate = if (usageIndex >= 0) {
            val usage = messages[usageIndex].usage!!
            val reportedTotal = usage.totalTokens.takeIf { it > 0 }
                ?: (usage.promptTokens + usage.completionTokens)
            // Tool outputs are attached to the assistant message only after the provider has
            // reported usage for its tool-call response. They therefore are not represented in
            // that usage figure, even though the next model request includes them.
            reportedTotal.toLong() +
                estimatePostUsageToolOutputTokens(messages[usageIndex]) +
                messages.drop(usageIndex + 1).sumOf(::estimateMessageTokens)
        } else {
            messages.sumOf(::estimateMessageTokens)
        }
        return estimate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Estimates the context represented by the supplied messages without using provider usage.
     * This is required after compaction because reported usage belongs to the pre-compaction
     * request and cannot describe the synthetic summary that replaced it.
     */
    fun estimateContextTokens(messages: List<UIMessage>): Int = messages
        .sumOf(::estimateMessageTokens)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    fun estimateMessageTokens(message: UIMessage): Long {
        // After DocumentAsPromptTransformer has run, the document text already sits in a Text
        // part of the same message; counting the Document part again would double it.
        val documentsInlined = message.parts.any { part ->
            part is UIMessagePart.Text && isInlinedDocumentText(part.text)
        }
        val contentTokens = message.parts.sumOf { part ->
            if (documentsInlined && part is UIMessagePart.Document) 0L else estimatePartTokens(part)
        }
        return MESSAGE_OVERHEAD_TOKENS + contentTokens
    }

    /** True for the `<UploadFile …>` text that DocumentAsPromptTransformer prepends. */
    fun isInlinedDocumentText(text: String): Boolean =
        text.trimStart().startsWith(INLINED_DOCUMENT_TAG)

    /**
     * Estimates only the execution results that were appended after provider usage was emitted.
     * Tool-call names and arguments already belong to the assistant completion represented by the
     * reported usage, so counting them here would double-count that response.
     */
    @Suppress("DEPRECATION")
    private fun estimatePostUsageToolOutputTokens(message: UIMessage): Long = message.parts.sumOf { part ->
        when (part) {
            is UIMessagePart.Tool -> part.output.sumOf(::estimatePartTokens)
            is UIMessagePart.ToolResult -> estimateTextTokens(part.content.toString())
            else -> 0L
        }
    }

    /** Selects a recent tail whose estimated size fits [targetTokens]. */
    fun chooseTailStartIndex(
        messages: List<UIMessage>,
        targetTokens: Int,
        minimumRecentMessages: Int = 4,
    ): Int {
        if (messages.size <= 1) return 0

        val minimumStart = (messages.size - minimumRecentMessages.coerceAtLeast(1))
            .coerceAtLeast(0)
        var start = messages.lastIndex
        var used = 0L
        while (start >= 0) {
            val next = estimateMessageTokens(messages[start])
            if (start < minimumStart && used + next > targetTokens) break
            used += next
            start--
        }
        return (start + 1).coerceIn(1, messages.lastIndex)
    }

    @Suppress("DEPRECATION")
    private fun estimatePartTokens(part: UIMessagePart): Long = when (part) {
        is UIMessagePart.Text -> estimateTextTokens(part.text)
        is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
        is UIMessagePart.Tool -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.input) + part.output.sumOf(::estimatePartTokens)
        is UIMessagePart.ServerTool -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.input.toString()) + estimateTextTokens(part.output.toString())
        is UIMessagePart.ToolCall -> estimateTextTokens(part.toolName) + estimateTextTokens(part.arguments)
        is UIMessagePart.ToolResult -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.arguments.toString()) + estimateTextTokens(part.content.toString())
        // Real size of what the transformer inlined last time (clamped to the inline budget);
        // after a restart, estimated from the extracted-text cache or the file size. The fixed
        // guess remains only for documents that are not local files.
        is UIMessagePart.Document -> (DocumentTokenEstimates.estimate(part) ?: MEDIA_PART_TOKENS.toLong()) +
            estimateTextTokens(part.fileName)
        is UIMessagePart.Image,
        is UIMessagePart.Video,
        is UIMessagePart.Audio,
        UIMessagePart.Search,
            -> MEDIA_PART_TOKENS.toLong()
    }

    /**
     * Conservative token estimate: every non-ASCII char (CJK, emoji, …) counts as one token,
     * ASCII as one token per [CHARS_PER_TOKEN] chars. Real tokenizers usually need fewer.
     */
    fun estimateTextTokens(text: String): Long {
        var asciiChars = 0L
        var nonAsciiChars = 0L
        text.forEach { char ->
            if (char.code <= 0x7F) asciiChars++ else nonAsciiChars++
        }
        return nonAsciiChars + (asciiChars + CHARS_PER_TOKEN - 1L) / CHARS_PER_TOKEN
    }
}
