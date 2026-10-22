package com.clma.app.data.ai

import com.clma.ai.core.MessageRole
import com.clma.ai.provider.Model
import com.clma.ai.registry.ModelRegistry
import com.clma.ai.ui.UIMessage
import kotlinx.datetime.LocalDateTime

/** What to do with a new user turn before its request goes out. */
sealed interface PreSendContextDecision {
    /** Send as is. */
    data object Send : PreSendContextDecision

    /** Automatic compaction is on and the request already reaches its trigger: compact first. */
    data class Compact(val estimatedTokens: Int, val triggerTokens: Int) : PreSendContextDecision

    /**
     * Automatic compaction is off and the request is clearly larger than the model's window: it
     * would only come back as a "context too long" error (and be paid for), so do not send it.
     */
    data class OverHardLimit(val estimatedTokens: Int, val limitTokens: Int) : PreSendContextDecision
}

/**
 * Pre-send check for a new user turn.
 *
 * Automatic compaction otherwise only runs after a tool result (driven by provider-reported
 * usage) or after the provider already rejected a request as too long. A turn that starts over
 * the window - a long chat, a big attachment, the "继续" after a failed reply - was therefore
 * always sent, and always failed.
 *
 * Why this is cheap and does not over-trigger, which is why the original code avoided
 * estimating here:
 * - It only runs at the start of a new user turn (the last message is the user's). Tool
 *   continuations and approvals inside a turn keep the usage-driven path, so the model still
 *   gets to run its tools before anything is compacted.
 * - [ContextBudgetPlanner.estimateInputTokens] anchors on the newest provider-reported usage
 *   (which already includes system prompt and tool schemas) and only estimates the messages after
 *   it - typically just the new user message - so it is both accurate and O(new messages).
 * - After a compaction, usage reported before it describes the old, larger request; it is then
 *   ignored and the compacted view is estimated directly, the same check the compaction code
 *   uses to size its tail, so a fresh compaction does not immediately trigger another one.
 * - The hard-limit refusal only fires [HARD_LIMIT_TOLERANCE_PERCENT] over the known window, so
 *   the conservative estimator or a slightly rounded registry value cannot block a request
 *   that would have fit.
 */
object PreSendContextGuard {
    const val HARD_LIMIT_TOLERANCE_PERCENT = 110

    /**
     * The model's real window for the hard-limit check: provider metadata, else the registry.
     * Deliberately ignores the TOKENS-mode threshold, which is a compaction trigger the user
     * picked (8K by default), not a model limit.
     */
    fun hardContextLimit(model: Model): Int? = ContextWindowResolver.resolve(
        declaredCeiling = null,
        providerWindow = model.contextLength,
        registryWindow = ModelRegistry.MODEL_CONTEXT_LENGTH.getData(model.modelId),
    )

    /**
     * Estimated input tokens of [messages] (the request view). When [compactedAt] is set, usage
     * reported by a message created before that compaction is not trusted.
     */
    fun estimateTokens(messages: List<UIMessage>, compactedAt: LocalDateTime?): Int {
        if (compactedAt == null) return ContextBudgetPlanner.estimateInputTokens(messages)
        val latestUsage = messages.lastOrNull { message ->
            val usage = message.usage
            usage != null && (usage.totalTokens > 0 || usage.promptTokens + usage.completionTokens > 0)
        }
        return if (latestUsage != null && latestUsage.createdAt >= compactedAt) {
            ContextBudgetPlanner.estimateInputTokens(messages)
        } else {
            ContextBudgetPlanner.estimateContextTokens(messages)
        }
    }

    fun decide(
        messages: List<UIMessage>,
        autoCompactionEnabled: Boolean,
        triggerTokens: Int?,
        hardLimitTokens: Int?,
        compactedAt: LocalDateTime? = null,
    ): PreSendContextDecision {
        // Only a brand-new user turn; tool resumes / approvals keep the usage-driven path.
        if (messages.lastOrNull()?.role != MessageRole.USER) return PreSendContextDecision.Send
        if (autoCompactionEnabled) {
            val trigger = triggerTokens?.takeIf { it > 0 } ?: return PreSendContextDecision.Send
            val estimated = estimateTokens(messages, compactedAt)
            return if (estimated >= trigger) {
                PreSendContextDecision.Compact(estimated, trigger)
            } else {
                PreSendContextDecision.Send
            }
        }
        val limit = hardLimitTokens?.takeIf { it > 0 } ?: return PreSendContextDecision.Send
        val estimated = estimateTokens(messages, compactedAt)
        val refuseAbove = limit.toLong() * HARD_LIMIT_TOLERANCE_PERCENT / 100L
        return if (estimated > refuseAbove) {
            PreSendContextDecision.OverHardLimit(estimated, limit)
        } else {
            PreSendContextDecision.Send
        }
    }
}
