package me.rerere.rikkahub.subagent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * One parent-conversation turn, already reduced to the text the sub-agent will see.
 */
data class SubAgentContextTurn(val role: String, val text: String)

/**
 * T-04 / (4) — "context refs" for a sub-agent dispatch.
 *
 * A sub-agent runs in a CLEAN context: its conversation starts empty and its first (and
 * only, until it works) user message is the task text. There is no shared history object
 * and no way to "point at" a message, so the only mechanism that can work is to
 * **materialise** the caller's referenced history as text inside that first message.
 *
 * This file is that materialisation, kept free of Android / repository types so it can be
 * unit-tested directly. The engine does the IO ([SubAgentEngine.withParentContext]) and
 * hands the resulting messages to [turnsFrom].
 *
 * Budgets exist because the whole point of dispatch is a *small* clean context: a
 * sub-agent that inherits 40k characters of parent history has no advantage over doing
 * the work inline.
 */
object SubAgentContextDigest {
    /** Hard ceiling on how many parent turns a single dispatch may carry. */
    const val MAX_TURNS = 10

    /** Per-turn cap. Over-budget turns keep their head AND tail (see [clampTurn]). */
    const val MAX_CHARS_PER_TURN = 2000

    /** Total cap across all carried turns; oldest turns are dropped first. */
    const val MAX_TOTAL_CHARS = 8000

    const val TRUNCATION_MARKER = " …[truncated]… "

    const val HEADER =
        "Context from the parent conversation (background only — the TASK below is what you must do):"

    const val FOOTER = "End of parent context."

    /**
     * Reduce persisted messages to context turns.
     *
     * Rules:
     *  - Trailing assistant messages are dropped. At dispatch time the newest one is the
     *    very message carrying the dispatch call — it is still being generated, so showing
     *    it back to the sub-agent would hand it a half-finished turn (usually a bare tool
     *    call) and invite it to "answer" that instead of the task.
     *  - Only TEXT parts travel. Tool calls/results/images are dropped by construction, so
     *    turns that reduce to nothing are dropped too.
     *  - Keeps the NEWEST [maxTurns] turns; [maxTurns] is clamped to 0..[MAX_TURNS].
     */
    fun turnsFrom(messages: List<UIMessage>, maxTurns: Int = MAX_TURNS): List<SubAgentContextTurn> {
        val limit = maxTurns.coerceIn(0, MAX_TURNS)
        if (limit == 0) return emptyList()
        return messages
            .dropLastWhile { it.role == MessageRole.ASSISTANT }
            .mapNotNull { message ->
                val text = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .trim()
                if (text.isEmpty()) null else SubAgentContextTurn(message.role.name.lowercase(), text)
            }
            .takeLast(limit)
    }

    /**
     * D3 — the audio/video parts of the NEWEST user message, in order.
     *
     * A sub-agent starts from an empty conversation, so the only way a recording the user just
     * made can reach it is by copying the parts into its first message. Images deliberately
     * stay out: [turnsFrom] drops them "by construction", and D3 only opened the channel for
     * the two part types the omni models can actually ingest. An empty list (no user message,
     * or none carrying media) means "behave exactly as before D3".
     */
    fun mediaPartsFrom(messages: List<UIMessage>): List<UIMessagePart> =
        messages.lastOrNull { it.role == MessageRole.USER }
            ?.parts
            ?.filter { it is UIMessagePart.Audio || it is UIMessagePart.Video }
            ?: emptyList()

    /**
     * Render turns into the block that gets prepended to the task, or null when there is
     * nothing worth carrying (empty input, all-blank turns) — callers treat null as
     * "behave exactly as before T-04".
     */
    fun render(turns: List<SubAgentContextTurn>): String? {
        val cleaned = turns
            .map { SubAgentContextTurn(it.role.trim().lowercase(), it.text.trim()) }
            .filter { it.text.isNotEmpty() }
            .takeLast(MAX_TURNS)
        if (cleaned.isEmpty()) return null

        // Fill from the newest turn backwards so that when the total budget bites, the
        // turns that survive are the ones nearest the current question.
        val kept = ArrayDeque<SubAgentContextTurn>()
        var used = 0
        for (turn in cleaned.asReversed()) {
            val body = clampTurn(turn.text)
            val cost = body.length + turn.role.length + 4
            // The newest turn is always kept: it is the one the task almost certainly
            // refers to, and clampTurn already bounds it to MAX_CHARS_PER_TURN.
            if (kept.isNotEmpty() && used + cost > MAX_TOTAL_CHARS) break
            kept.addFirst(SubAgentContextTurn(turn.role, body))
            used += cost
        }

        val omitted = cleaned.size - kept.size
        return buildString {
            append(HEADER)
            append('\n')
            if (omitted > 0) {
                append("($omitted older turn(s) omitted for length.)")
                append('\n')
            }
            kept.forEach { turn ->
                append('[').append(turn.role).append("] ").append(turn.text).append('\n')
            }
            append(FOOTER)
        }
    }

    /** Convenience for the common "render if you can, else leave the task alone" case. */
    fun prependToTask(turns: List<SubAgentContextTurn>, task: String): String {
        val block = render(turns) ?: return task
        return block + "\n\n" + task
    }

    /**
     * Keep the head and the tail of an over-long turn. A parent turn that opens with the
     * question and closes with the conclusion is far more useful to a sub-agent than a
     * plain prefix, and the marker makes the cut explicit so the model doesn't read a
     * severed sentence as the whole story.
     */
    private fun clampTurn(text: String): String {
        if (text.length <= MAX_CHARS_PER_TURN) return text
        val keep = (MAX_CHARS_PER_TURN - TRUNCATION_MARKER.length).coerceAtLeast(0) / 2
        return text.take(keep) + TRUNCATION_MARKER + text.takeLast(keep)
    }
}
