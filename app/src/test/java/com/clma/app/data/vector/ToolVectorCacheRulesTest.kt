package com.clma.app.data.vector

import com.clma.app.data.ai.tools.ToolCatalogEntry
import com.clma.app.data.ai.tools.ToolCatalogSource
import com.clma.app.data.ai.tools.ToolRankFusion
import com.clma.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — the tool vector cache contract.
 *
 * The cases that matter: a warm entry is reused, a rewritten description invalidates exactly that
 * entry, and a different embedding model invalidates everything (its vectors live in another
 * space, so reusing them would be comparing incomparable numbers).
 */
class ToolVectorCacheRulesTest {

    private val modelId = "edg2-q8"
    private val otherModel = "qwen3-0.6b"

    private fun entry(
        name: String,
        summary: String = "summary for $name",
    ) = ToolCatalogEntry(
        name = name,
        summary = summary,
        source = ToolCatalogSource.MCP,
        tool = Tool(name = name, description = summary, execute = { emptyList() }),
    )

    private fun cacheOf(vararg pairs: Pair<ToolCatalogEntry, FloatArray>): Map<String, CachedToolVector> =
        pairs.associate { (e, v) ->
            ToolVectorCacheRules.key(modelId, e.name) to
                CachedToolVector(ToolVectorCacheRules.textHash(e), v)
        }

    @Test
    fun `the key carries both the model and the name`() {
        assertEquals("edg2-q8|tap", ToolVectorCacheRules.key(modelId, "tap"))
        assertTrue(
            ToolVectorCacheRules.key(modelId, "tap") != ToolVectorCacheRules.key(otherModel, "tap"),
        )
    }

    @Test
    fun `an empty cache is missing every entry`() {
        val entries = listOf(entry("tap"), entry("scroll"))
        assertEquals(entries, ToolVectorCacheRules.missing(modelId, entries, emptyMap()))
        assertFalse(ToolVectorCacheRules.ready(modelId, entries, emptyMap()))
    }

    @Test
    fun `a cached unchanged entry is looked up and no longer missing`() {
        val tap = entry("tap")
        val cache = cacheOf(tap to floatArrayOf(1f, 0f))
        assertNotNull(ToolVectorCacheRules.lookup(modelId, tap, cache))
        assertTrue(ToolVectorCacheRules.missing(modelId, listOf(tap), cache).isEmpty())
        assertTrue(ToolVectorCacheRules.ready(modelId, listOf(tap), cache))
    }

    @Test
    fun `a rewritten description invalidates exactly that entry`() {
        // An MCP server can change a tool's description between sessions; a stale vector would keep
        // ranking the old wording, which is the failure this fingerprint exists to prevent.
        val tap = entry("tap")
        val before = entry("scroll", summary = "scroll the screen")
        val cache = cacheOf(tap to floatArrayOf(1f), before to floatArrayOf(0f, 1f))

        val after = entry("scroll", summary = "scroll a list to its end")
        assertNull(ToolVectorCacheRules.lookup(modelId, after, cache))
        assertEquals(listOf(after), ToolVectorCacheRules.missing(modelId, listOf(tap, after), cache))
        assertFalse(ToolVectorCacheRules.ready(modelId, listOf(tap, after), cache))
    }

    @Test
    fun `a different model misses every entry`() {
        val tap = entry("tap")
        val cache = cacheOf(tap to floatArrayOf(1f))
        assertTrue(ToolVectorCacheRules.ready(modelId, listOf(tap), cache))
        assertFalse(ToolVectorCacheRules.ready(otherModel, listOf(tap), cache))
        assertEquals(listOf(tap), ToolVectorCacheRules.missing(otherModel, listOf(tap), cache))
    }

    @Test
    fun `an empty catalogue is never ready`() {
        // Readiness is a statement about a non-empty catalogue: "ready" on nothing would let a
        // caller skip the check that keeps it from embedding an empty list.
        assertFalse(ToolVectorCacheRules.ready(modelId, emptyList(), emptyMap()))
    }

    @Test
    fun `the embedded text is the same one the ranker would embed`() {
        val entry = entry("take_screenshot", summary = "Capture the current display")
        assertEquals(
            ToolRankFusion.embedText(entry.name, entry.summary),
            ToolVectorCacheRules.embedTextOf(entry),
        )
        assertEquals("take screenshot Capture the current display", ToolVectorCacheRules.embedTextOf(entry))
    }
}
