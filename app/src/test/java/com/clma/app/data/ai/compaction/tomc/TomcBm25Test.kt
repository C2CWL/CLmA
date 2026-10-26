package com.clma.app.data.ai.compaction.tomc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retrieval half of TOMC is pure arithmetic, so it can be pinned exactly. These cases are the
 * four behaviours the compressor relies on: CJK bigrams exist at all, a ubiquitous term scores
 * zero, a rare shared term wins, and "no match" degrades to recency instead of to nothing.
 */
class TomcBm25Test {

    private fun index(vararg docs: String) = TomcBm25(docs.map { TomcTokenizer.tokens(it) })

    @Test
    fun `tokenizer keeps cjk bigrams and latin words`() {
        val tokens = TomcTokenizer.tokens("修复 ChatService.kt 的空指针")
        assertTrue(tokens.contains("修复"))
        assertTrue(tokens.contains("chatservice"))
        assertTrue(tokens.contains("kt"))

        val english = TomcTokenizer.tokens("the quick fix")
        assertTrue(english.contains("quick"))
        assertTrue(english.contains("fix"))
        assertTrue(english.none { it == "the" })
    }

    @Test
    fun `a term present in every document carries no weight`() {
        val bm25 = index("alpha common", "beta common", "gamma common")
        assertEquals(0.0, bm25.score(TomcTokenizer.tokens("common"), 0), 0.0001)
    }

    @Test
    fun `ranking prefers the document that shares a rare term`() {
        val bm25 = index("aa bb", "bb cc", "cc dd")
        assertEquals(listOf(0), bm25.rank(TomcTokenizer.tokens("aa")))
    }

    @Test
    fun `equal scores keep document order so the same history compresses to the same bytes`() {
        val bm25 = index("aa bb", "aa cc", "dd ee")
        assertEquals(listOf(0, 1), bm25.rank(TomcTokenizer.tokens("aa")))
        assertEquals(listOf(0), bm25.rank(TomcTokenizer.tokens("aa"), limit = 1))
    }

    @Test
    fun `no match falls back to recency instead of an empty list`() {
        val bm25 = index("aa bb", "bb cc", "cc dd")
        assertEquals(listOf(2, 1, 0), bm25.rank(TomcTokenizer.tokens("nothere")))
        assertEquals(listOf(2, 1, 0), bm25.rank(emptyList()))
        assertEquals(listOf(2), bm25.rank(emptyList(), limit = 1))
        assertEquals(emptyList<Int>(), TomcBm25(emptyList()).rank(emptyList()))
    }
}
