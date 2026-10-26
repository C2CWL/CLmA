package com.clma.app.data.ai.compaction.tomc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline baseline for the local track: TOMC against "just keep the tail of the chat".
 *
 * The tail window is not a straw man — it is what the app effectively does today when the
 * budget runs out and no LLM compaction has happened yet, and on a long thread it is where the
 * "it forgot what we decided" reports come from. The history below is built the way real ones
 * decay: the settled facts land in the first few turns, then forty turns of chatter, then a
 * couple of "ok / continue" turns. A tail window of the same size cannot see the facts; the
 * ledger is supposed to.
 *
 * Runs in the JVM, no network, no Android — the point is a number, not a vibe. The LLM track is
 * deliberately not compared here: measuring it means paying for real requests, and it is the
 * wrong default baseline anyway (it is not the fallback).
 */
class TomcCompressionBenchmarkTest {

    /** Distinctive strings, so a match cannot be coincidence. */
    private val needles = listOf("sdk-34", "sdk-35", "0.7.0-alpha1", "com.clma.app", "只做兜底")

    private fun history(): List<TomcTurn> {
        val settled = listOf(
            "target_sdk = sdk-34",
            "compile_sdk = sdk-35",
            "versionName = 0.7.0-alpha1",
            "package_name = com.clma.app",
            "决定：本地压缩只做兜底，默认仍然走 LLM 摘要",
        )
        val chatter = (1..40).map { i ->
            "第 $i 轮：继续推进界面细节，聊到列表滚动、动画时长和配色，没有新的结论。"
        }
        return (settled + chatter + listOf("好，那就这样", "继续")).mapIndexed { index, text ->
            TomcTurn(index, if (index % 2 == 0) "user" else "assistant", text)
        }
    }

    private fun recall(text: String): Double =
        needles.count { text.contains(it) }.toDouble() / needles.size

    private fun tail(history: List<TomcTurn>, budget: Int): String =
        history.joinToString("\n") { it.text }.takeLast(budget)

    @Test
    fun `settled facts survive TOMC and vanish from a tail-only window`() {
        val history = history()
        val budget = 1200
        val compressed = TomcCompressor.compress(history, "现在 target_sdk 和 versionName 分别是什么", budget)
        val tailRecall = recall(tail(history, budget))
        val tomcRecall = recall(compressed.text)

        println(
            "TOMC baseline: budget=$budget route=${compressed.route} used=${compressed.usedChars} " +
                "facts=${compressed.factCount} excerptTurns=${compressed.excerptTurns.size} " +
                "tomcRecall=$tomcRecall tailRecall=$tailRecall"
        )

        assertTrue("TOMC must respect the budget", compressed.usedChars <= budget)
        assertEquals(1.0, tomcRecall, 0.0)
        assertTrue("a tail window of the same size lost the facts: $tailRecall", tailRecall < tomcRecall)
        // The recency fill is capped, so a wall of near-identical chatter cannot soak up the
        // excerpt budget: 3 assignment turns + the newest turn + at most 3 filler turns.
        assertTrue("excerpt picks: ${compressed.excerptTurns}", compressed.excerptTurns.size <= 7)
    }

    @Test
    fun `a squeeze to a quarter of the budget keeps the ledger and the newest turn`() {
        val history = history()
        val budget = 300
        val compressed = TomcCompressor.compress(history, "现在配置是什么", budget)

        println(
            "TOMC squeeze: budget=$budget route=${compressed.route} used=${compressed.usedChars} " +
                "facts=${compressed.factCount} tomcRecall=${recall(compressed.text)} " +
                "tailRecall=${recall(tail(history, budget))}"
        )

        assertTrue("TOMC must respect the budget", compressed.usedChars <= budget)
        assertTrue("the ledger must survive the squeeze", compressed.text.contains("sdk-34"))
        assertTrue("the newest turn is what the next request is about", compressed.excerptTurns.contains(history.lastIndex))
    }
}
