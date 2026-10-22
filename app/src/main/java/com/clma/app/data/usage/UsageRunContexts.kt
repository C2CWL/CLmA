package me.rerere.rikkahub.data.usage

import java.util.concurrent.ConcurrentHashMap

/**
 * P2-12d — process-scoped registry telling the usage ledger which autonomous run a model call
 * belongs to.
 *
 * The ledger has carried `run_id` / `parent_run_id` columns since P2-11 (and the orchestration
 * tree and the P2-13 budget gate both read them), but nothing ever filled them: every one of the
 * six `UsageCallContext(...)` constructions in the codebase left the two fields null, so a
 * sub-agent's calls landed as anonymous rows and the parent→child tree had no data source at all.
 *
 * The reason the fields cannot simply be threaded down from the dispatcher is coroutine topology:
 * a sub-agent run enters the chat pipeline through `chatService.sendMessage(convId, …)`, which
 * launches the generation on `appScope` rather than running it inline, so the dispatcher's own
 * coroutine context never reaches the model call. This registry bridges that gap the same way
 * [me.rerere.rikkahub.data.ai.tools.HeadlessConversations] bridges the headless flag: the
 * dispatcher registers the child's conversation id before handing it to `ChatService`, and
 * `GenerationLoop` looks the attribution up when it builds the call's `UsageCallContext`.
 *
 * The mapping lives for the length of the run — [mark] when the child conversation is created,
 * [unmarkRun] in the run's `finally` — so it never accumulates and, crucially, a later interactive
 * turn on the same conversation can never inherit a finished run's id (a finished child's
 * conversation is headless and never reused, but the registry does not rely on that).
 *
 * Process-scoped and deliberately NOT persisted: a run's attribution is only meaningful while the
 * process that launched it is alive. If the process dies the run dies too (`AgentRunBootRecovery`
 * flips its row to `process_lost`), so there is nothing to restore.
 *
 * Pure by construction — no Room, no Android, no coroutines — so the mark/lookup semantics are
 * unit-testable without a runtime.
 */
object UsageRunContexts {

    /**
     * The run a conversation's model calls belong to.
     *
     * [runId] is the `AgentRun.domainId` of the run driving this conversation (for a sub-agent,
     * its run id), [parentRunId] is the orchestration root everything under it is summed by
     * (for a sub-agent, the parent conversation id — the same value `SubAgentEngine` writes to
     * `agent_runs.parent_run_id`), and [purpose] is what the call is filed as.
     */
    data class Attribution(
        val runId: String,
        val parentRunId: String?,
        val purpose: UsagePurpose,
    )

    private val byConversation = ConcurrentHashMap<String, Attribution>()

    /** run id -> the conversation it drives, so [unmarkRun] can find the forward entry. */
    private val conversationByRun = ConcurrentHashMap<String, String>()

    /**
     * Register [conversationId] as driven by [runId] under orchestration root [parentRunId].
     *
     * A conversation belongs to exactly one run at a time, so re-marking replaces the previous
     * attribution. Both directions of the mapping are cleaned before the new one is written:
     * the conversation's old run loses its forward entry, and the run's old conversation loses its
     * entry. Skipping either half would leave one of the two maps pointing at a pair that no longer
     * exists — and [unmarkRun] on that stale entry would then delete a live conversation's
     * attribution.
     */
    fun mark(
        conversationId: String,
        runId: String,
        parentRunId: String?,
        purpose: UsagePurpose = UsagePurpose.SUBAGENT,
    ) {
        byConversation[conversationId]?.let { previous ->
            if (previous.runId != runId) conversationByRun.remove(previous.runId, conversationId)
        }
        conversationByRun[runId]?.let { previousConversation ->
            if (previousConversation != conversationId) byConversation.remove(previousConversation)
        }
        byConversation[conversationId] = Attribution(runId = runId, parentRunId = parentRunId, purpose = purpose)
        conversationByRun[runId] = conversationId
    }

    /** The attribution for [conversationId], or null when it is not a tracked run's conversation. */
    fun get(conversationId: String?): Attribution? =
        conversationId?.let { byConversation[it] }

    /** Drop the conversation's entry (its run is still tracked by run id). */
    fun unmarkConversation(conversationId: String) {
        val removed = byConversation.remove(conversationId) ?: return
        conversationByRun.remove(removed.runId, conversationId)
    }

    /** Drop everything for [runId]; the run's `finally` calls this. */
    fun unmarkRun(runId: String) {
        val conversationId = conversationByRun.remove(runId) ?: return
        byConversation.remove(conversationId)
    }

    /** Number of conversations currently tracked (test/observability only). */
    val size: Int get() = byConversation.size

    /** Clear everything (tests / process reset). */
    fun clear() {
        byConversation.clear()
        conversationByRun.clear()
    }
}
