package me.rerere.rikkahub.data.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.agentrun.AgentRun
import me.rerere.rikkahub.data.agentrun.AgentRunKind

/**
 * P2-12d — the parent→child orchestration tree behind the statistics page's new section.
 *
 * The row of a sub-agent run lives in `agent_runs` (kind `subagent`) and carries a `parent_run_id`
 * pointing at the conversation that dispatched it; the tokens it cost live in `usage_records`,
 * keyed by `run_id` — the run's `domainId`, the same value `agent_runs.domain_id` holds, never
 * `agent_runs.id`. Neither table alone answers "what did this dispatch cost", so this file joins
 * them in Kotlin: `agent_runs` supplies the tree's shape and every node's lifecycle, the ledger
 * supplies its numbers.
 *
 * The shape is deliberately only two levels deep: the recursion guard keeps sub-agent dispatch to
 * one layer (D11), so a parent is a conversation with no `agent_runs` row of its own — a
 * sub-agent's parent is an interactive turn, which never opens a ledger row. The "parent" is
 * therefore a conversation id (and, in the UI, its title), not a node with usage of its own.
 *
 * Pure by construction — [AgentRun] and [UsageRecordEntity] are plain data classes whose only
 * compile-time dependency is the androidx.room annotation set — so the grouping and summing rules
 * are unit-testable without a database. Two invariants are inherited from P2-11 / P2-12c and are
 * load-bearing here too:
 *  - a run with no ledger rows shows zero calls and zero tokens rather than being dropped — the
 *    tree must still show that a dispatch happened even when its calls were never recorded;
 *  - a missing price stays missing: null is never summed as zero, and a tree whose children have
 *    no priced call reports a null cost rather than "$0.00".
 */

/** One sub-agent run: its lifecycle from `agent_runs` plus its usage summed from the ledger. */
data class OrchestrationNode(
    val runId: String,
    val label: String,
    /** [me.rerere.rikkahub.data.agentrun.AgentRunStatus] name, shown as-is. */
    val status: String,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val callCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val providerCostUsd: Double?,
    val costMicros: Long?,
) {
    val totalTokens: Long get() = inputTokens + outputTokens

    /** The only price known was recomputed from the local price table, so the UI marks it. */
    val estimatedCostOnly: Boolean get() = providerCostUsd == null && costMicros != null

    /** False when `agent_runs` has the run but the ledger has no row for it. */
    val hasLedgerRows: Boolean get() = callCount > 0

    val isRunning: Boolean get() = status in IN_FLIGHT_STATUS
}

/**
 * One dispatch: a parent conversation and every sub-agent run it fanned out, newest run first.
 *
 * [parentConversationId] is null for a sub-agent whose row carried no parent (a dispatch from a
 * path that does not record one); the UI shows those under a generic heading rather than hiding
 * them.
 */
data class OrchestrationTree(
    val parentConversationId: String?,
    val children: List<OrchestrationNode>,
    /**
     * P2-14d — the ceiling the parent assistant configured for orchestration, or null when none is
     * set. Read from [me.rerere.rikkahub.data.model.Assistant.orchestrationTokenBudget]; an expert's
     * own override is not recoverable from a finished ledger window, so the tree reports the
     * assistant-level ceiling only.
     */
    val budget: Long? = null,
) {
    val childCount: Int get() = children.size
    val callCount: Int get() = children.sumOf { it.callCount }
    val inputTokens: Long get() = children.sumOf { it.inputTokens }
    val outputTokens: Long get() = children.sumOf { it.outputTokens }
    val totalTokens: Long get() = inputTokens + outputTokens

    /** Sum of the children that have a provider price; null when none do. */
    val providerCostUsd: Double? get() = sumOrNull(children.mapNotNull { it.providerCostUsd })

    /** Sum of the children that have a table price; null when none do. */
    val costMicros: Long? get() = sumOrNull(children.mapNotNull { it.costMicros })

    /** Newest child start, so the UI can order dispatches most-recent-first. */
    val latestAtMs: Long get() = children.maxOfOrNull { it.startedAtMs } ?: 0L

    val hasRunningChild: Boolean get() = children.any { it.isRunning }

    /** P2-14d — true when a ceiling is configured; the statistics page only then shows a footer. */
    val hasBudget: Boolean get() = budget != null

    /**
     * P2-14d — [OrchestrationBudget.remaining] applied to this tree: the ceiling minus the tokens its
     * children actually billed. Null ("unlimited") stays distinct from 0 ("nothing left"), so a tree
     * with no configured ceiling renders no footer at all. This is the production caller P2-07's
     * `remaining()` was written for and never had.
     */
    val remaining: Long? get() = OrchestrationBudget.remaining(totalTokens, budget)

    /** P2-14d — true once the children have spent the ceiling, mirroring the P2-13 dispatch gate. */
    val budgetExceeded: Boolean get() = OrchestrationBudget.exceeded(totalTokens, budget)

    private fun sumOrNull(values: List<Double>): Double? = if (values.isEmpty()) null else values.sum()

    private fun sumOrNull(values: List<Long>): Long? = if (values.isEmpty()) null else values.sum()
}

object OrchestrationTreeFactory {

    /** Newest dispatch first, capped so a runaway ledger can never render an unbounded list. */
    const val DEFAULT_MAX_TREES = 20

    /**
     * Build the dispatches from a window of `agent_runs` rows and the ledger rows that price them.
     *
     * Only `kind == subagent` rows become tree nodes: cron / workflow / telegram / external runs
     * are not a parent's fan-out, and expert-library / price-table writes are not runs at all.
     *
     * @param runs recent rows from [me.rerere.rikkahub.data.agentrun.AgentRunRepository.getRecent],
     *        any order.
     * @param records ledger rows covering at least the same window, any order. Rows with a null
     *        `run_id` (interactive turns, auxiliary calls) are ignored — they belong to no run.
     * @param maxTrees how many dispatches to return, newest first.
     * @param budgetOfConversation P2-14d — the ceiling configured for a dispatch's parent
     *        conversation, or null for none. Injected rather than read here so this file stays pure
     *        (the assistant is a settings object, behind Android); the statistics page supplies it.
     */
    fun build(
        runs: List<AgentRun>,
        records: List<UsageRecordEntity>,
        maxTrees: Int = DEFAULT_MAX_TREES,
        budgetOfConversation: (String?) -> Long? = { null },
    ): List<OrchestrationTree> {
        val children = runs.filter { it.kind == AgentRunKind.SubAgent.wire }
        if (children.isEmpty()) return emptyList()

        val usageByRun = HashMap<String, NodeAcc>()
        for (row in records) {
            val runId = row.runId ?: continue
            usageByRun.getOrPut(runId) { NodeAcc() }.add(row)
        }

        val grouped = LinkedHashMap<String?, MutableList<AgentRun>>()
        for (run in children) grouped.getOrPut(run.parentRunId) { mutableListOf() }.add(run)

        return grouped.map { (parentId, parentRuns) ->
            val nodes = parentRuns
                .sortedBy { it.createdAtMs }
                .map { run -> toNode(run, usageByRun[run.domainId]) }
            OrchestrationTree(
                parentConversationId = parentId,
                children = nodes,
                budget = budgetOfConversation(parentId),
            )
        }
            .sortedByDescending { it.latestAtMs }
            .take(maxTrees)
    }

    private fun toNode(run: AgentRun, acc: NodeAcc?): OrchestrationNode = OrchestrationNode(
        runId = run.id,
        label = readLabel(run.metadataJson) ?: run.domainId.take(8),
        status = run.status,
        startedAtMs = run.createdAtMs,
        finishedAtMs = run.finishedAtMs,
        callCount = acc?.calls ?: 0,
        inputTokens = acc?.input ?: 0L,
        outputTokens = acc?.output ?: 0L,
        providerCostUsd = acc?.providerCost,
        costMicros = acc?.costMicros,
    )

    /** `agent_runs.metadata_json` carries `{"label": …}` for sub-agents; best-effort. */
    private val json = Json { ignoreUnknownKeys = true }

    private fun readLabel(metadataJson: String?): String? = metadataJson?.let { raw ->
        runCatching {
            (json.parseToJsonElement(raw) as? JsonObject)
                ?.get("label")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()
    }?.takeIf { it.isNotBlank() }

    private class NodeAcc {
        var calls = 0
        var input = 0L
        var output = 0L
        var providerCost: Double? = null
        var costMicros: Long? = null

        fun add(row: UsageRecordEntity) {
            calls++
            input += row.inputTokens
            output += row.outputTokens
            // Missing stays missing: a null price is never summed as zero.
            row.providerCostUsd?.let { providerCost = (providerCost ?: 0.0) + it }
            row.costMicros?.let { costMicros = (costMicros ?: 0L) + it }
        }
    }
}

/** Statuses that count as "still running" — mirrors [me.rerere.rikkahub.data.agentrun.AgentRunStatus]. */
private val IN_FLIGHT_STATUS = setOf("queued", "awaiting_approval", "running")
