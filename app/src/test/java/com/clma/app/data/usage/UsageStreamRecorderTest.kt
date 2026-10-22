package me.rerere.rikkahub.data.usage

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageStreamRecorderTest {

    private fun usage(prompt: Int, completion: Int, total: Int = prompt + completion) =
        TokenUsage(promptTokens = prompt, completionTokens = completion, totalTokens = total)

    /** A chunk that carries usage, and one that does not. */
    private val usageChunk = "Usage" to usage(1, 2)
    private val textChunk = "Text" to null

    @Test
    fun `passes every element through unchanged`() = runBlocking {
        val recorded = mutableListOf<TokenUsage>()
        val out = flowOf("a", "b", "c")
            .recordUsageOnce(select = { null }, record = { recorded += it })
            .toList()

        assertEquals(listOf("a", "b", "c"), out)
        assertTrue("nothing selected -> nothing recorded", recorded.isEmpty())
    }

    @Test
    fun `merges the selected figures and records once when the stream completes`() = runBlocking {
        val recorded = mutableListOf<TokenUsage>()
        val out = flowOf(usageChunk, textChunk, "Usage" to usage(0, 3))
            .recordUsageOnce(select = { it.second }, record = { recorded += it })
            .toList()

        assertEquals(listOf(usageChunk, textChunk, "Usage" to usage(0, 3)), out)
        assertEquals(1, recorded.size)
        val row = recorded.single()
        assertEquals(1, row.promptTokens) // the later chunk reported 0 -> the first value stands
        assertEquals(3, row.completionTokens) // last non-zero wins
        assertEquals(3, row.totalTokens)
    }

    @Test
    fun `a failed stream records nothing and propagates the failure`() = runBlocking {
        val recorded = mutableListOf<TokenUsage>()
        val boom = IllegalStateException("socket closed")
        val flow = flow {
            emit(usageChunk)
            throw boom
        }.recordUsageOnce(select = { it.second }, record = { recorded += it })

        var thrown: Throwable? = null
        try {
            flow.toList()
        } catch (t: Throwable) {
            thrown = t
        }

        assertSame(boom, thrown)
        assertTrue("a stream that never completed must not be recorded", recorded.isEmpty())
    }

    @Test
    fun `a re-collection never writes a second row`() = runBlocking {
        val recorded = mutableListOf<TokenUsage>()
        val flow = flowOf(usageChunk)
            .recordUsageOnce(select = { it.second }, record = { recorded += it })

        flow.toList()
        flow.toList()

        assertEquals(1, recorded.size)
    }

    @Test
    fun `a throwing recorder does not break the stream`() = runBlocking {
        val out = flowOf("x", "y")
            .recordUsageOnce(select = { usage(1, 1) }, record = { error("ledger down") })
            .toList()

        assertEquals(listOf("x", "y"), out)
    }
}
