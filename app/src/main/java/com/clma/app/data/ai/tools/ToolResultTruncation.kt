package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.ContextCompactionPlanner

/**
 * Phase 17 (⑥) — optional per-tool-result token budget.
 *
 * Upstream already spills oversized tool output to `/tool_outputs/` when a workspace shell is
 * available (see `GenerationLoop.maybeTruncateToolOutput`), but that gate is a fixed 32 KB of
 * *characters* and the preview keeps only the **head** — the tail, which is usually where a
 * command's exit status, stack trace or conclusion lives, is exactly what gets dropped.
 *
 * This is the pure, testable core of the optional tighter budget:
 *
 * - `null` or non-positive budget → callers must keep the upstream behaviour (no-op here);
 * - over budget → keep head + tail, elide the middle, and report the arithmetic so the caller
 *   can spill the full text and tell the model where to find it.
 *
 * It deliberately performs **no IO** and touches no Android APIs: the caller owns the spill.
 */
data class TruncationOutcome(
    /** Text to put in the tool result. Equals the input when [truncated] is false. */
    val text: String,
    val truncated: Boolean,
    /** Token estimate of the original text (same estimator as the compaction planner). */
    val originalTokens: Int,
    /** Token estimate of [text]. */
    val resultTokens: Int,
    /** Characters kept from the start of the original text. */
    val headChars: Int,
    /** Characters kept from the end of the original text. */
    val tailChars: Int,
    /** Characters dropped from the middle. `headChars + tailChars + elidedChars == original length`. */
    val elidedChars: Int,
)

/** Fraction of the budget spent on the head; the rest goes to the tail. */
const val DEFAULT_TOOL_RESULT_HEAD_RATIO: Double = 0.6

/**
 * Tokens held back so the elision marker + the caller's notice header can't push the result
 * back over the budget. The marker renders to roughly 24 tokens, so 32 leaves headroom.
 * For degenerate budgets (<= 32 tokens) the content is clamped to a single token and the
 * marker may overshoot — it is metadata, not content, and dropping it would leave the model
 * with silently-missing data.
 */
private const val MARKER_RESERVE_TOKENS = 32

/**
 * Trims [text] to roughly [maxTokens] tokens, keeping the head (60% by default) and the tail
 * (the remaining 40%) and replacing the middle with a marker.
 *
 * @param maxTokens `null` (or <= 0) disables the budget entirely — the input is returned as-is
 *   with `truncated = false`. An invalid budget is treated as "off" rather than "empty" so a
 *   typo in a settings field can never silently blank out a tool result.
 * @param headRatio clamped to `0.0..1.0`; `0.0` keeps only the tail, `1.0` only the head.
 */
fun truncateToolResult(
    text: String,
    maxTokens: Int?,
    headRatio: Double = DEFAULT_TOOL_RESULT_HEAD_RATIO,
): TruncationOutcome {
    val originalTokens = ContextCompactionPlanner.estimateTokens(text)

    if (maxTokens == null || maxTokens <= 0 || originalTokens <= maxTokens) {
        return TruncationOutcome(
            text = text,
            truncated = false,
            originalTokens = originalTokens,
            resultTokens = originalTokens,
            headChars = 0,
            tailChars = 0,
            elidedChars = 0,
        )
    }

    val budget = (maxTokens - MARKER_RESERVE_TOKENS).coerceAtLeast(1)
    val ratio = headRatio.coerceIn(0.0, 1.0)
    val headBudget = (budget * ratio).toInt().coerceIn(0, budget)
    val tailBudget = budget - headBudget

    val headEnd = advanceByTokens(text, from = 0, to = text.length, budget = headBudget, forward = true)
    val tailStart = advanceByTokens(text, from = text.length, to = 0, budget = tailBudget, forward = false)

    // Budget too small to split, or the two slices would overlap: keep the head only.
    if (tailBudget <= 0 || tailStart <= headEnd) {
        val head = text.substring(0, headEnd).trimEnd()
        val elided = text.length - head.length
        val rendered = head + elisionMarker(elided)
        return TruncationOutcome(
            text = rendered,
            truncated = true,
            originalTokens = originalTokens,
            resultTokens = ContextCompactionPlanner.estimateTokens(rendered),
            headChars = head.length,
            tailChars = 0,
            elidedChars = elided,
        )
    }

    val head = text.substring(0, headEnd)
    val tail = text.substring(tailStart)
    val elided = tailStart - headEnd
    val rendered = head + elisionMarker(elided) + tail
    return TruncationOutcome(
        text = rendered,
        truncated = true,
        originalTokens = originalTokens,
        resultTokens = ContextCompactionPlanner.estimateTokens(rendered),
        headChars = head.length,
        tailChars = tail.length,
        elidedChars = elided,
    )
}

/**
 * Builds the human-readable marker dropped into the middle of a truncated tool result. Kept
 * short on purpose: it is paid for out of the same budget as the content.
 */
fun elisionMarker(elidedChars: Int): String =
    "\n…[$elidedChars characters elided to fit the tool-result budget]…\n"

/**
 * Walks [text] between [from] (inclusive) and [to] (exclusive), advancing while the running
 * token estimate stays within [budget], and returns the index reached.
 *
 * Walking forward from 0 yields the end of the head slice; walking backward from
 * `text.length` yields the start of the tail slice. The estimator mirrors
 * [ContextCompactionPlanner.estimateTokens]: ASCII costs 1/3 token, everything else 1 token.
 */
private fun advanceByTokens(
    text: String,
    from: Int,
    to: Int,
    budget: Int,
    forward: Boolean,
): Int {
    if (budget <= 0) return from
    var ascii = 0L
    var nonAscii = 0L
    var index = from
    while (index != to) {
        val char = text[if (forward) index else index - 1]
        if (char.code <= 0x7F) ascii++ else nonAscii++
        val tokens = nonAscii + (ascii + 2L) / 3L
        if (tokens > budget) break
        index += if (forward) 1 else -1
    }
    return index
}
