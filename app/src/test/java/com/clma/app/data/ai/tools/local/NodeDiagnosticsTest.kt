package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "did you mean these?" ranking behind a `no_match`. The point of the feature is to stop
 * a run from stalling on one wrong guess, so what matters is that a near-miss ranks first and
 * that unrelated labels never show up as suggestions.
 */
class NodeDiagnosticsTest {

    @Test
    fun `an exact value is a perfect match`() {
        assertEquals(1.0, NodeDiagnostics.similarity("发送", "发送"), 0.0001)
        assertEquals(1.0, NodeDiagnostics.similarity("Send", "send"), 0.0001)
    }

    @Test
    fun `containment beats an unrelated neighbour`() {
        // "发送消息" contains the wanted "发送"; "取消" has nothing in common with it.
        assertTrue(NodeDiagnostics.similarity("发送", "发送消息") > 0.7)
        assertTrue(NodeDiagnostics.similarity("发送", "取消") < NodeDiagnostics.MIN_SCORE)
    }

    @Test
    fun `works for latin labels too`() {
        assertTrue(NodeDiagnostics.similarity("Send", "Send message") > 0.7)
        assertTrue(NodeDiagnostics.similarity("Send", "Cancel") < NodeDiagnostics.MIN_SCORE)
    }

    @Test
    fun `blank input never matches`() {
        assertEquals(0.0, NodeDiagnostics.similarity("", "发送"), 0.0001)
        assertEquals(0.0, NodeDiagnostics.similarity("Send", "   "), 0.0001)
    }

    @Test
    fun `ranking puts the closest value first and keeps indices valid`() {
        val values = listOf("取消", "发送消息", "分享")

        val ranked = NodeDiagnostics.rank("发送", values)

        assertEquals(1, ranked.first().index)
        assertEquals("发送消息", ranked.first().value)
        // Never suggests something below the usefulness floor.
        assertTrue(ranked.none { it.value == "取消" || it.value == "分享" })
    }

    @Test
    fun `ranking is capped, de-duplicated and deterministic on ties`() {
        val values = List(10) { "发送$it" } + listOf("发送0")

        val ranked = NodeDiagnostics.rank("发送", values, limit = 3)

        assertTrue(ranked.size <= 3)
        assertEquals(ranked.map { it.value }.distinct().size, ranked.size)
        // Same input, same answer.
        assertEquals(ranked, NodeDiagnostics.rank("发送", values, limit = 3))
    }
}
