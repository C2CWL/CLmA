package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.usage.TurnFooter

/**
 * Conversation token consumption shown in the chat top bars (regular and clean layout), summed
 * across all turns and tool calls. The usage ledger ([turnFooters]) is the ground truth (it
 * includes every tool-loop step); [TokenBudgetTracker] is the fallback for legacy or imported
 * conversations without ledger rows.
 */
internal fun conversationUsedTokens(turnFooters: Map<String, TurnFooter>, conversation: Conversation): Long =
    usedTokensOrFallback(
        ledgerTokens = turnFooters.values.mapNotNull { it.usage }.sumOf { it.inputTokens + it.outputTokens },
        fallback = { TokenBudgetTracker.aggregate(conversation).totalTokens },
    )

/** Ledger total when there is one, else the [fallback] estimate. */
internal fun usedTokensOrFallback(ledgerTokens: Long, fallback: () -> Long): Long =
    if (ledgerTokens > 0L) ledgerTokens else fallback()

/** "Assistant / Model / N token", the subtitle line of both chat top bars. */
internal fun topBarSubtitle(assistantName: String, modelName: String, usedTokens: Long): String =
    "$assistantName / $modelName / $usedTokens token"
