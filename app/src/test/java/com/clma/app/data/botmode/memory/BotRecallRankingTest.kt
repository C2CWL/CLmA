package com.clma.app.data.botmode.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRecallRankingTest {
    @Test
    fun matchesMixedChineseAndEnglish() {
        val terms = botRecallTerms("请问Kotlin协程怎么取消？").toSet()
        assertTrue(terms.containsAll(setOf("kotlin", "协程", "取消")))
        assertEquals(3, botRecallMatch("取消 Kotlin 协程", terms)?.score)
    }

    @Test
    fun rejectsSingleTermMatchForLongQueries() {
        val terms = botRecallTerms("kotlin coroutine cancellation timeout retry").toSet()
        assertNull(botRecallMatch("Kotlin syntax", terms))
    }

    @Test
    fun excerptIncludesMatchFarFromStart() {
        val text = "Opening sentence. ".repeat(80) + "The deployment uses a staging cluster."
        val match = requireNotNull(botRecallMatch(text, setOf("deployment", "staging")))
        assertEquals(2, match.score)
        assertTrue(match.excerpt.startsWith("…"))
        assertTrue(match.excerpt.contains("deployment uses a staging cluster"))
        assertTrue(match.excerpt.length <= 602)
    }
}
