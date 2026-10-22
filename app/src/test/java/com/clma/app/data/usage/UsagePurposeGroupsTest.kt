package me.rerere.rikkahub.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D10 — the coarse purpose grouping behind the statistics page's "by purpose" ranking.
 */
class UsagePurposeGroupsTest {

    private fun bucket(
        key: String,
        calls: Int = 1,
        input: Long = 10L,
        output: Long = 5L,
        cacheHit: Long = 0L,
        cachePrompt: Long = 0L,
        cacheReported: Boolean = false,
        providerCostUsd: Double? = null,
        costMicros: Long? = null,
        measuredOutputTokens: Long = 0L,
        generationMs: Long = 0L,
    ) = UsageStatBucket(
        key = key,
        callCount = calls,
        inputTokens = input,
        outputTokens = output,
        cacheHitTokens = cacheHit,
        cachePromptTokens = cachePrompt,
        cacheReported = cacheReported,
        providerCostUsd = providerCostUsd,
        costMicros = costMicros,
        measuredOutputTokens = measuredOutputTokens,
        generationMs = generationMs,
    )

    @Test
    fun `the main turn and its tool loop merge into one conversation bucket`() {
        val merged = UsagePurposeGroups.merge(
            listOf(
                bucket(UsagePurpose.MAIN.name, calls = 2, input = 300L, output = 30L),
                bucket(UsagePurpose.TOOL_LOOP.name, calls = 3, input = 100L, output = 20L),
            ),
        )

        assertEquals(1, merged.size)
        val conversation = merged.single()
        assertEquals(UsagePurposeGroups.CONVERSATION, conversation.key)
        assertEquals(5, conversation.callCount)
        assertEquals(400L, conversation.inputTokens)
        assertEquals(50L, conversation.outputTokens)
    }

    @Test
    fun `cron and workflow fold into automation`() {
        val merged = UsagePurposeGroups.merge(
            listOf(
                bucket(UsagePurpose.CRON.name),
                bucket(UsagePurpose.WORKFLOW.name),
            ),
        )

        assertEquals(listOf(UsagePurposeGroups.AUTOMATION), merged.map { it.key })
        assertEquals(2, merged.single().callCount)
    }

    @Test
    fun `the assistant's auxiliary call sites fold into one bucket`() {
        val merged = UsagePurposeGroups.merge(
            listOf(
                bucket(UsagePurpose.TITLE.name),
                bucket(UsagePurpose.SUGGESTION.name),
                bucket(UsagePurpose.COMPACTION.name),
                bucket(UsagePurpose.MEMORY_EXTRACT.name),
                bucket(UsagePurpose.TRANSLATION.name),
                bucket(UsagePurpose.SKILL_TEST.name),
            ),
        )

        assertEquals(listOf(UsagePurposeGroups.ASSISTANT), merged.map { it.key })
        assertEquals(6, merged.single().callCount)
    }

    @Test
    fun `an unrecognised purpose falls into other`() {
        assertEquals(UsagePurposeGroups.OTHER, UsagePurposeGroups.groupOf("NOT_A_PURPOSE"))
        assertEquals(UsagePurposeGroups.OTHER, UsagePurposeGroups.groupOf(UsageStatsFactory.UNKNOWN_KEY))
    }

    @Test
    fun `the merged ranking is largest first`() {
        val merged = UsagePurposeGroups.merge(
            listOf(
                bucket(UsagePurpose.TITLE.name, input = 1L, output = 0L),
                bucket(UsagePurpose.SUBAGENT.name, input = 900L, output = 0L),
                bucket(UsagePurpose.MAIN.name, input = 100L, output = 0L),
            ),
        )

        assertEquals(
            listOf(UsagePurposeGroups.SUBAGENT, UsagePurposeGroups.CONVERSATION, UsagePurposeGroups.ASSISTANT),
            merged.map { it.key },
        )
    }

    @Test
    fun `a cache hit rate survives a merge only when a row reported cache fields`() {
        val withoutCache = UsagePurposeGroups.merge(
            listOf(
                bucket(UsagePurpose.MAIN.name, input = 100L, cacheReported = false),
                bucket(UsagePurpose.TOOL_LOOP.name, input = 100L, cacheReported = false),
            ),
        ).single()
        assertNull(withoutCache.cacheHitRate)

        val withCache = UsagePurposeGroups.merge(
            listOf(
                bucket(UsagePurpose.MAIN.name, input = 100L, cacheHit = 50L, cachePrompt = 100L, cacheReported = true),
                bucket(UsagePurpose.TOOL_LOOP.name, input = 100L, cacheReported = false),
            ),
        ).single()
        assertEquals(50, withCache.cacheHitRate)
        assertEquals(210L, withCache.totalTokens)
    }

    @Test
    fun `a price stays missing only when no merged row was priced`() {
        assertNull(
            UsagePurposeGroups.merge(
                listOf(
                    bucket(UsagePurpose.MAIN.name),
                    bucket(UsagePurpose.TOOL_LOOP.name),
                ),
            ).single().providerCostUsd,
        )

        val priced = UsagePurposeGroups.merge(
            listOf(
                bucket(UsagePurpose.MAIN.name, providerCostUsd = 0.25),
                bucket(UsagePurpose.TOOL_LOOP.name),
            ),
        ).single()
        assertEquals(0.25, priced.providerCostUsd!!, 1e-9)
    }

    @Test
    fun `an empty ranking merges to an empty ranking`() {
        assertTrue(UsagePurposeGroups.merge(emptyList()).isEmpty())
    }
}
