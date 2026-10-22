package com.clma.app.data.ai.document

import com.clma.app.data.ai.ContextBudgetPlanner

/**
 * Decides how much of each attached document can be inlined into a request.
 *
 * Rule: a document that fits is inlined whole (unchanged behaviour). Only when it does not fit
 * does it get cut to the head that fits, and the model is told so (see [truncationNotice]).
 *
 * Budget = (context window − reserved output) × (100 − [SAFETY_MARGIN_PERCENT])% − everything
 * else in the request. Documents are served newest first, so the document the user just sent
 * wins over older ones further up the history.
 *
 * Pure (no Android, no IO) so the arithmetic is unit-tested directly.
 */
object DocumentInlineBudget {
    /** Window assumed when neither the user, the provider nor the model registry knows one. */
    const val DEFAULT_CONTEXT_TOKENS = 128_000

    /** Output reserved when the assistant has no explicit max-tokens setting. */
    const val DEFAULT_RESERVED_OUTPUT_TOKENS = 16_000

    /** Headroom for tool schemas, provider overhead and estimator error. */
    const val SAFETY_MARGIN_PERCENT = 10

    /** Even when the budget is exhausted every document still shows this much of its head. */
    const val MIN_PREVIEW_TOKENS = 1_000

    /** Tokens kept back for the truncation notice itself. */
    const val NOTICE_RESERVE_TOKENS = 300

    /**
     * Tokens available for all documents together.
     *
     * @param contextLength effective model window, or null when unknown ([DEFAULT_CONTEXT_TOKENS]).
     * @param maxOutputTokens the assistant's max output tokens, or null ([DEFAULT_RESERVED_OUTPUT_TOKENS]).
     *   Never reserves more than half of the window.
     * @param otherTokens estimate of everything in the request except the documents.
     */
    fun availableTokens(contextLength: Int?, maxOutputTokens: Int?, otherTokens: Long): Long {
        val window = (contextLength?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS).toLong()
        val reserved = (maxOutputTokens?.takeIf { it > 0 } ?: DEFAULT_RESERVED_OUTPUT_TOKENS)
            .toLong()
            .coerceAtMost(window / 2)
        val usable = (window - reserved) * (100 - SAFETY_MARGIN_PERCENT) / 100
        return (usable - otherTokens.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    /** How one document is inlined. */
    data class Allocation(
        val fitsWhole: Boolean,
        /** Token budget for this document's text; equals its full size when [fitsWhole]. */
        val tokens: Long,
    )

    /**
     * Allocates [available] tokens to documents of the given sizes, which must be ordered
     * newest first. The result is in the same order.
     */
    fun allocate(documentTokensNewestFirst: List<Long>, available: Long): List<Allocation> {
        var remaining = available.coerceAtLeast(0L)
        return documentTokensNewestFirst.map { size ->
            if (size <= remaining) {
                remaining -= size
                Allocation(fitsWhole = true, tokens = size)
            } else {
                val share = (remaining - NOTICE_RESERVE_TOKENS).coerceAtLeast(MIN_PREVIEW_TOKENS.toLong())
                remaining = 0L
                Allocation(fitsWhole = false, tokens = share.coerceAtMost(size))
            }
        }
    }

    /** Result of cutting a text down to a token budget. */
    data class Head(
        val text: String,
        /** Characters kept, counted from the start of the original text. */
        val chars: Int,
        /** 1-based number of the last line (fully or partly) included. */
        val lastLine: Int,
        /** 1-based line where reading should continue. */
        val nextLine: Int,
    )

    /**
     * Longest head of [text] whose estimate (same estimator as [ContextBudgetPlanner]) stays
     * within [maxTokens]. When a line break is close to the cut (last 20% of the head) the head
     * ends there, so the continuation starts on a fresh line.
     */
    fun headWithinTokens(text: String, maxTokens: Long): Head {
        var ascii = 0L
        var nonAscii = 0L
        var end = 0
        while (end < text.length) {
            if (text[end].code <= 0x7F) ascii++ else nonAscii++
            if (nonAscii + (ascii + 2L) / 3L > maxTokens) break
            end++
        }
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline >= 0 && newline + 1 >= end * 4 / 5) end = newline + 1
        }
        val head = text.substring(0, end)
        val newlines = head.count { it == '\n' }
        val endsOnLineBreak = head.endsWith('\n')
        val lastLine = when {
            head.isEmpty() -> 0
            endsOnLineBreak -> newlines
            else -> newlines + 1
        }
        // After a clean line break the next line is newlines + 1; a cut in the middle of a line
        // also continues at newlines + 1 (that same, partly shown line) so nothing is skipped.
        val nextLine = newlines + 1
        return Head(text = head, chars = end, lastLine = lastLine, nextLine = nextLine)
    }

    /** Number of lines as workspace_read_file counts them. */
    fun lineCount(text: String): Int =
        if (text.isEmpty()) 0 else text.removeSuffix("\n").count { it == '\n' } + 1

    /** Percentage of [totalChars] shown, at least 1 when anything is shown, at most 99. */
    fun shownPercent(shownChars: Int, totalChars: Int): Int {
        if (totalChars <= 0 || shownChars <= 0) return 0
        return (shownChars.toLong() * 100L / totalChars).toInt().coerceIn(1, 99)
    }

    /**
     * Notice appended after a truncated document, telling the model what it is missing and how
     * to read the rest. [fullTextPath] is the workspace path of the full extracted text, or null
     * when no such file exists.
     */
    fun truncationNotice(
        shownChars: Int,
        totalChars: Int,
        totalTokens: Long,
        totalLines: Int,
        head: Head,
        fullTextPath: String?,
    ): String = buildString {
        val percent = shownPercent(shownChars, totalChars)
        append("[DOCUMENT TRUNCATED] Only the beginning of this document is included above: ")
        append("$shownChars of $totalChars characters (about $percent%), ")
        append("because the whole document (~$totalTokens tokens) does not fit in the model's context window.")
        if (fullTextPath != null) {
            append("\nThe full extracted text is saved at $fullTextPath ($totalLines lines; ")
            append("the part above ends at line ${head.lastLine}). ")
            append("To read the rest, call workspace_read_file on that path with offset/limit ")
            append("(e.g. offset=${head.nextLine}, limit=500) and continue chunk by chunk; ")
            append("never read the whole file at once. ")
            append("If you do not have the workspace_read_file tool, tell the user that you could only see ")
            append("the first $percent% of the document.")
        } else {
            append("\nThe rest of the document is not available in this conversation. ")
            append("Tell the user that you could only see the first $percent% of the document.")
        }
        append("]")
    }

    /** Same estimator as [ContextBudgetPlanner], exposed for callers that size documents. */
    fun estimateTokens(text: String): Long = ContextBudgetPlanner.estimateTextTokens(text)
}
