package me.rerere.rikkahub.data.usage

import me.rerere.rikkahub.data.agentrun.AgentRun
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-12d — the orchestration tree's grouping and summing rules.
 *
 * `agent_runs` supplies the shape, `usage_records` the numbers, and the rules that matter are the
 * two inherited invariants: a run with no ledger rows still appears (with zero), and a missing
 * price is never summed as zero.
 */
class OrchestrationTreeTest {

    private var rowSeq = 0

    private fun run(
        id: String,
        parent: String? = null,
        createdAt: Long = 0L,
        kind: String = AgentRunKind.SubAgent.wire,
        status: String = "succeeded",
        finishedAt: Long? = null,
        label: String? = null,
    ): AgentRun = AgentRun(
        id = id,
        kind = kind,
        domainId = "domain-$id",
        parentRunId = parent,
        status = status,
        createdAtMs = createdAt,
        updatedAtMs = createdAt,
        finishedAtMs = finishedAt,
        metadataJson = label?.let { """{"label":"$it"}""" },
    )

    private fun row(
        runId: String?,
        input: Int = 0,
        output: Int = 0,
        providerCost: Double? = null,
        tableCostMicros: Long? = null,
    ): UsageRecordEntity = UsageRecordEntity(
        id = "row-${rowSeq++}",
        createdAtMs = 0L,
        purpose = UsagePurpose.SUBAGENT.name,
        runId = runId,
        inputTokens = input,
        outputTokens = output,
        totalTokens = input + output,
        cachedTokens = 0,
        cachedTokensReported = false,
        providerCostUsd = providerCost,
        costMicros = tableCostMicros,
    )

    @Test
    fun `no runs produce no trees`() {
        assertTrue(OrchestrationTreeFactory.build(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `only subagent runs become tree nodes`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(
                run("cron-1", kind = AgentRunKind.Cron.wire),
                run("wf-1", kind = AgentRunKind.Workflow.wire),
                run("sub-1", parent = "parent-1"),
            ),
            records = emptyList(),
        )

        assertEquals(1, trees.size)
        assertEquals(listOf("sub-1"), trees.single().children.map { it.runId })
    }

    @Test
    fun `children are grouped by their parent conversation`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(
                run("a", parent = "parent-1", createdAt = 10L),
                run("b", parent = "parent-2", createdAt = 20L),
                run("c", parent = "parent-1", createdAt = 30L),
            ),
            records = emptyList(),
        )

        val byParent = trees.associateBy { it.parentConversationId }
        assertEquals(listOf("a", "c"), byParent.getValue("parent-1").children.map { it.runId })
        assertEquals(listOf("b"), byParent.getValue("parent-2").children.map { it.runId })
    }

    @Test
    fun `children are ordered oldest first`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(
                run("late", parent = "p", createdAt = 300L),
                run("early", parent = "p", createdAt = 100L),
                run("mid", parent = "p", createdAt = 200L),
            ),
            records = emptyList(),
        )

        assertEquals(listOf("early", "mid", "late"), trees.single().children.map { it.runId })
    }

    @Test
    fun `usage is summed by the run domain id`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(run("a", parent = "p", createdAt = 10L)),
            records = listOf(
                row("domain-a", input = 100, output = 20),
                row("domain-a", input = 50, output = 5),
            ),
        )

        val node = trees.single().children.single()
        assertEquals(2, node.callCount)
        assertEquals(150L, node.inputTokens)
        assertEquals(25L, node.outputTokens)
        assertEquals(175L, node.totalTokens)
    }

    @Test
    fun `a ledger row keyed by the run id is not attributed`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(run("a", parent = "p")),
            records = listOf(row("a", input = 137, output = 41)),
        )

        // usage_records.run_id is AgentRun.domainId, so a row keyed by the run's own id
        // belonged to no run and must not be summed (D11).
        val node = trees.single().children.single()
        assertFalse(node.hasLedgerRows)
        assertEquals(0L, node.totalTokens)
    }

    @Test
    fun `a run with no ledger rows shows zero calls and tokens`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(run("a", parent = "p")),
            records = listOf(row("other-run", input = 999)),
        )

        val node = trees.single().children.single()
        assertEquals(0, node.callCount)
        assertEquals(0L, node.totalTokens)
        assertFalse(node.hasLedgerRows)
    }

    @Test
    fun `records without a run id are ignored`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(run("a", parent = "p")),
            records = listOf(row(runId = null, input = 500, output = 500)),
        )

        assertEquals(0L, trees.single().children.single().totalTokens)
    }

    @Test
    fun `a subagent without a parent gets its own tree with a null parent`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(run("orphan", parent = null, createdAt = 5L)),
            records = emptyList(),
        )

        assertEquals(null, trees.single().parentConversationId)
        assertEquals(listOf("orphan"), trees.single().children.map { it.runId })
    }

    @Test
    fun `trees are ordered newest first`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(
                run("old", parent = "p-old", createdAt = 100L),
                run("new", parent = "p-new", createdAt = 900L),
                run("mid", parent = "p-mid", createdAt = 500L),
            ),
            records = emptyList(),
        )

        assertEquals(listOf("p-new", "p-mid", "p-old"), trees.map { it.parentConversationId })
    }

    @Test
    fun `max trees keeps the newest dispatches`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(
                run("a", parent = "p1", createdAt = 100L),
                run("b", parent = "p2", createdAt = 200L),
                run("c", parent = "p3", createdAt = 300L),
            ),
            records = emptyList(),
            maxTrees = 2,
        )

        assertEquals(listOf("p3", "p2"), trees.map { it.parentConversationId })
    }

    @Test
    fun `provider cost sums only the priced children`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(
                run("a", parent = "p", createdAt = 10L),
                run("b", parent = "p", createdAt = 20L),
            ),
            records = listOf(
                row("domain-a", providerCost = 0.25),
                row("domain-b"), // no price at all
            ),
        )

        assertEquals(0.25, trees.single().providerCostUsd!!, 1e-9)
    }

    @Test
    fun `a tree with no priced child reports a null cost rather than zero`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(run("a", parent = "p")),
            records = listOf(row("domain-a", input = 10)),
        )

        assertNull(trees.single().providerCostUsd)
        assertNull(trees.single().costMicros)
    }

    @Test
    fun `estimatedCostOnly is true when only the table price is known`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(run("a", parent = "p")),
            records = listOf(row("domain-a", tableCostMicros = 1_500_000L)),
        )

        val node = trees.single().children.single()
        assertTrue(node.estimatedCostOnly)
        assertEquals(1_500_000L, node.costMicros)
    }

    @Test
    fun `label is read from the run metadata`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(run("a", parent = "p", label = "research the docs")),
            records = emptyList(),
        )

        assertEquals("research the docs", trees.single().children.single().label)
    }

    @Test
    fun `label falls back to the domain id when metadata is missing`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(run("a", parent = "p")),
            records = emptyList(),
        )

        // domain-a -> first 8 characters
        assertEquals("domain-a", trees.single().children.single().label)
    }

    @Test
    fun `tree totals are the sum of the children`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(
                run("a", parent = "p", createdAt = 10L),
                run("b", parent = "p", createdAt = 20L),
            ),
            records = listOf(
                row("domain-a", input = 100, output = 10, providerCost = 0.10),
                row("domain-b", input = 200, output = 20, providerCost = 0.20),
            ),
        )

        val tree = trees.single()
        assertEquals(2, tree.childCount)
        assertEquals(2, tree.callCount)
        assertEquals(300L, tree.inputTokens)
        assertEquals(30L, tree.outputTokens)
        assertEquals(330L, tree.totalTokens)
        assertEquals(0.30, tree.providerCostUsd!!, 1e-9)
    }

    @Test
    fun `has running child is true for an in-flight status`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(
                run("done", parent = "p", createdAt = 10L, status = "succeeded"),
                run("live", parent = "p", createdAt = 20L, status = "running"),
            ),
            records = emptyList(),
        )

        assertTrue(trees.single().hasRunningChild)
    }

    @Test
    fun `finished at is carried through and start is the created at`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(run("a", parent = "p", createdAt = 42L, finishedAt = 99L)),
            records = emptyList(),
        )

        val node = trees.single().children.single()
        assertEquals(42L, node.startedAtMs)
        assertEquals(99L, node.finishedAtMs)
    }
}
