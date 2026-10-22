package me.rerere.rikkahub.data.usage

/**
 * P2-07 — per-orchestration budget arithmetic (decision D8).
 *
 * An "orchestration" is one dispatch tree: a parent turn plus every sub-agent run it fans
 * out. Everything billed inside it is summed by `usage_records.parent_run_id`
 * ([UsageLedger.tokensForOrchestration]), and that sum is compared against a ceiling
 * before another dispatch may start — P2-13 is the caller that enforces the refusal and
 * returns the structured envelope; this file only owns the comparison it makes.
 *
 * Two budgets can be in play, and their precedence is the whole point:
 *  - the assistant's [me.rerere.rikkahub.data.model.Assistant.orchestrationTokenBudget],
 *    the default ceiling for every dispatch that assistant makes, and
 *  - an expert's [me.rerere.rikkahub.data.agentdef.AgentDefinition.tokenBudget], which
 *    **overrides** the assistant's value for the run that names that expert.
 *
 * `null` always means "unlimited", so the defaults cost nothing: with both values null
 * [effectiveBudget] returns null and [exceeded] is false for any usage — the red line that
 * a build with the feature untouched behaves exactly as it did before.
 *
 * Pure on purpose: no Room, no coroutine, no Android. The database read stays in
 * [UsageLedger]; this object is only the rule applied to its result, which is what makes
 * the rule unit-testable without a database.
 */
object OrchestrationBudget {

    /**
     * The ceiling to enforce for one dispatch: the expert's value when it set one, otherwise
     * the assistant's. A null in either place means unlimited — an expert that does not set
     * its own budget inherits whatever the assistant configured (D8, which is why the expert
     * column is nullable rather than defaulted to 0).
     */
    fun effectiveBudget(assistantBudget: Long?, expertBudget: Long?): Long? =
        expertBudget ?: assistantBudget

    /**
     * True when [usedTokens] has already reached [budget] — i.e. another dispatch could only
     * push the orchestration further over. A null budget is never exceeded.
     *
     * The comparison is `>=` rather than `>` on purpose: the budget is a ceiling on the whole
     * tree, so spending the last token fills it and the next dispatch is refused. A caller
     * that wants "refuse only once strictly over" would be describing a different contract.
     */
    fun exceeded(usedTokens: Long, budget: Long?): Boolean =
        budget != null && usedTokens >= budget

    /**
     * Tokens left before [budget] is reached, clamped at 0, or null when there is no budget.
     * A null result is distinct from 0 on purpose — "unlimited" and "nothing left" must not
     * render the same way in the ledger UI (P2-12).
     */
    fun remaining(usedTokens: Long, budget: Long?): Long? =
        budget?.let { (it - usedTokens).coerceAtLeast(0L) }
}
