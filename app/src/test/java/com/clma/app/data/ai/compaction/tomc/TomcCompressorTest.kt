package com.clma.app.data.ai.compaction.tomc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 42/58 half of TOMC: route detection, the ledger's supersede rule, the budget split, and the
 * guarantee that the assembled text never eats more characters than it was given.
 */
class TomcCompressorTest {

    private fun turns(vararg texts: String) = texts.mapIndexed { index, text ->
        TomcTurn(index, if (index % 2 == 0) "user" else "assistant", text)
    }

    @Test
    fun `route follows the question, not the history`() {
        assertEquals(TomcRoute.STATE, TomcRouteDetector.detect("现在 target_sdk 版本是什么"))
        assertEquals(TomcRoute.COUNT, TomcRouteDetector.detect("一共改了几个文件"))
        assertEquals(TomcRoute.RELATION, TomcRouteDetector.detect("谁调用了 ChatService"))
        assertEquals(TomcRoute.RAW, TomcRouteDetector.detect("我们继续"))
        assertEquals(TomcRoute.RAW, TomcRouteDetector.detect("   "))
    }

    @Test
    fun `a state question gives the ledger half the budget and a raw one a quarter`() {
        val state = TomcCompressor.plan(1000, TomcRoute.STATE)
        assertEquals(500, state.ledgerChars)
        assertEquals(500, state.excerptChars)

        val relation = TomcCompressor.plan(1000, TomcRoute.RELATION)
        assertEquals(420, relation.ledgerChars)
        assertEquals(580, relation.excerptChars)

        val raw = TomcCompressor.plan(1000, TomcRoute.RAW)
        assertEquals(250, raw.ledgerChars)
        assertEquals(750, raw.excerptChars)

        assertEquals(0, TomcCompressor.plan(0, TomcRoute.STATE).total)
    }

    @Test
    fun `a later assignment replaces the earlier one`() {
        val facts = TomcStateLedger.extract(turns("target_sdk = 34", "好", "target_sdk = 35"))
        val sdk = facts.filter { it.key == "target_sdk" }
        assertEquals(1, sdk.size)
        assertEquals("35", sdk.single().value)
        assertEquals(2, sdk.single().sourceIndex)
    }

    @Test
    fun `a sentence with spaces before the colon is not an assignment`() {
        // The ledger must not invent key/value pairs out of prose; a wrong "fact" is worse than
        // a missing one, because the model will believe it.
        val facts = TomcStateLedger.extract(turns("注意 这个 key 是 空的：真的"))
        assertTrue(facts.none { it.key.contains(' ') })
    }

    @Test
    fun `a tight ledger budget drops the oldest facts first`() {
        val facts = listOf(
            TomcFact("a", "1", 0, 3),
            TomcFact("b", "2", 1, 3),
            TomcFact("c", "3", 2, 3),
        )
        assertEquals("- b: 2\n- c: 3", TomcStateLedger.render(facts, 15))
        assertEquals("", TomcStateLedger.render(facts, 3))
    }

    @Test
    fun `compress stays inside the budget and always keeps the newest turn`() {
        val history = turns("target_sdk = 34", "好，那 ChatService 的压缩走本地轨道", "现在把 budget 调到 900")
        val result = TomcCompressor.compress(history, "继续", budgetChars = 200)
        assertTrue("used ${result.usedChars} of 200", result.usedChars <= 200)
        assertTrue(result.excerptTurns.contains(2))
        assertEquals(result.excerptTurns.sorted(), result.excerptTurns)
        assertTrue(result.text.contains("- target_sdk: 34"))
    }

    @Test
    fun `an empty history compresses to nothing rather than to a header`() {
        val result = TomcCompressor.compress(emptyList(), "继续", 400)
        assertEquals("", result.text)
        assertEquals(0, result.usedChars)
        assertEquals(emptyList<Int>(), result.excerptTurns)
    }
}
