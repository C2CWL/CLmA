package me.rerere.rikkahub.data.usage

/**
 * P2-13 — the dispatch gate that turns [OrchestrationBudget]'s arithmetic into a decision,
 * taken before a sub-agent is allowed to start.
 *
 * [OrchestrationBudget] owns the rule ("is this over the ceiling?"); this object owns the
 * answer the dispatcher hands back when it is: an [Decision.Allow] to proceed, or a
 * [Decision.Refuse] carrying the two numbers the caller needs to explain itself. It is pure —
 * no Room, no coroutine, no Android — so the refusal contract is unit-testable without a
 * database, and the caller ([me.rerere.rikkahub.subagent.SubAgentEngine]) stays a thin
 * "sum what was spent, ask the gate, raise the envelope" wrapper.
 *
 * Refusal is deliberately pre-flight and never a guess: a dispatch is judged against what the
 * orchestration has *already* spent, not against an estimate of what this one will cost. The
 * gate therefore cannot know that the run it is about to allow will be the one that tips the
 * tree over the ceiling — the *next* dispatch is the one refused. That is the contract D8
 * describes ("before another dispatch may start"), and it is also the only one enforceable
 * without a cost estimate.
 */
object OrchestrationGate {

    /** The stable `error` code the refusal envelope carries; models match on it. */
    const val ERROR_CODE = "budget_exceeded"

    sealed interface Decision {
        /** Nothing to enforce, or the ceiling is not reached yet: proceed. */
        data object Allow : Decision

        /**
         * The orchestration has reached its ceiling. [usedTokens] is what the ledger summed
         * and [budgetTokens] the ceiling it crossed.
         */
        data class Refuse(val usedTokens: Long, val budgetTokens: Long) : Decision {
            /**
             * How far past the ceiling the tree already is, clamped at 0. Kept because a model
             * acts on "2345 over" more usefully than on "at the limit".
             */
            val overByTokens: Long get() = (usedTokens - budgetTokens).coerceAtLeast(0L)
        }
    }

    /**
     * The whole rule. A null [budget] is unlimited and always allows — which is what makes an
     * install with no budget configured behave exactly as it did before this card. `used ==
     * budget` already refuses, because the budget is a ceiling on the tree rather than a
     * threshold to cross: spending the last token fills it (see [OrchestrationBudget.exceeded],
     * the single place that comparison lives).
     */
    fun decide(usedTokens: Long, budget: Long?): Decision {
        val ceiling = budget ?: return Decision.Allow
        if (!OrchestrationBudget.exceeded(usedTokens, ceiling)) return Decision.Allow
        return Decision.Refuse(usedTokens = usedTokens, budgetTokens = ceiling)
    }

    /**
     * The human- and model-readable `detail` for a refusal. It names both numbers, states the
     * cause (this conversation's own sub-agent dispatches) and the two ways out — raising the
     * ceiling or doing the work inline — so the model can act on the refusal instead of
     * retrying it. Red line 8: a refused dispatch is never a silent failure.
     */
    fun refusalDetail(decision: Decision.Refuse): String =
        "this conversation has already spent ${decision.usedTokens} of its " +
            "${decision.budgetTokens}-token orchestration budget on sub-agent runs " +
            "(${decision.overByTokens} over the ceiling), so no further dispatch is allowed. " +
            "Raise the assistant's per-orchestration token limit (or the expert's own token " +
            "budget), or do the work inline instead of dispatching."
}
