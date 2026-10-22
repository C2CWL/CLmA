package me.rerere.rikkahub.data.usage

import me.rerere.rikkahub.data.agentrun.AgentRun
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-14d — the budget footer of the orchestration tree.
 *
 * [OrchestrationBudget.remaining] has existed since P2-07 but had no production caller; the
 * statistics page is that caller now, so the pairing rule (which conversation gets which ceiling,
 * and what "no ceiling" reports) is pinned here.
 */
class OrchestrationTreeBudgetTest {

    private var rowSeq = 0

    private fun run(id: String, parent: String? = "p", createdAt: Long = 1L): AgentRun = AgentRun(
        id = id,
        kind = AgentRunKind.SubAgent.wire,
        domainId = "domain-$id",
        parentRunId = parent,
        status = "succeeded",
        createdAtMs = createdAt,
        updatedAtMs = createdAt,
    )

    private fun row(runId: String, input: Int, output: Int = 0): UsageRecordEntity = UsageRecordEntity(
        id = "row-${rowSeq++}",
        createdAtMs = 0L,
        purpose = UsagePurpose.SUBAGENT.name,
        runId = runId,
        inputTokens = input,
        outputTokens = output,
        totalTokens = input + output,
        cachedTokens = 0,
        cachedTokensReported = false,
        providerCostUsd = null,
        costMicros = null,
    )

    @Test
    fun `no ceiling configured reports a null remaining, distinct from zero`() {
        val tree = OrchestrationTreeFactory.build(
            runs = listOf(run("a")),
            records = listOf(row("domain-a", input = 10)),
        ).single()

        assertFalse(tree.hasBudget)
        assertNull(tree.remaining)
        assertFalse(tree.budgetExceeded)
    }

    @Test
    fun `remaining is the ceiling minus the tree total`() {
        val tree = OrchestrationTreeFactory.build(
            runs = listOf(run("a")),
            records = listOf(row("domain-a", input = 300, output = 200)),
            budgetOfConversation = { 10_000L },
        ).single()

        assertTrue(tree.hasBudget)
        assertEquals(500L, tree.totalTokens)
        assertEquals(9_500L, tree.remaining)
        assertFalse(tree.budgetExceeded)
    }

    @Test
    fun `spending exactly the ceiling leaves zero remaining and counts as over`() {
        val tree = OrchestrationTreeFactory.build(
            runs = listOf(run("a")),
            records = listOf(row("domain-a", input = 1_000)),
            budgetOfConversation = { 1_000L },
        ).single()

        assertEquals(0L, tree.remaining)
        assertTrue(tree.budgetExceeded)
    }

    @Test
    fun `remaining is clamped at zero once the ceiling is blown`() {
        val tree = OrchestrationTreeFactory.build(
            runs = listOf(run("a")),
            records = listOf(row("domain-a", input = 1_500)),
            budgetOfConversation = { 1_000L },
        ).single()

        assertEquals(0L, tree.remaining)
        assertTrue(tree.budgetExceeded)
    }

    @Test
    fun `the ceiling is looked up per parent conversation`() {
        val trees = OrchestrationTreeFactory.build(
            runs = listOf(
                run("a", parent = "conv-1", createdAt = 10L),
                run("b", parent = "conv-2", createdAt = 20L),
            ),
            records = listOf(row("domain-a", input = 100), row("domain-b", input = 100)),
            budgetOfConversation = { id -> if (id == "conv-1") 1_000L else null },
        ).associateBy { it.parentConversationId }

        assertEquals(900L, trees.getValue("conv-1").remaining)
        assertNull(trees.getValue("conv-2").remaining)
    }

    @Test
    fun `a parentless tree is looked up by the null key`() {
        val tree = OrchestrationTreeFactory.build(
            runs = listOf(run("orphan", parent = null)),
            records = listOf(row("domain-orphan", input = 40)),
            budgetOfConversation = { id -> if (id == null) 100L else null },
        ).single()

        assertEquals(60L, tree.remaining)
    }
}
