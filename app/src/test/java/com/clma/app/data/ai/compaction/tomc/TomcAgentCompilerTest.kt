package com.clma.app.data.ai.compaction.tomc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TomcAgentCompilerTest {
    private fun source(text: String, id: String = "m1", role: String = "user") =
        TomcAgentCompiler.Source(id, role, text)

    @Test
    fun `empty input and unusable budgets do not fabricate evidence`() {
        assertEquals("", TomcAgentCompiler.compile(emptyList(), "", 1000).text)
        for (budget in listOf(-1, 0, 1)) {
            val result = TomcAgentCompiler.compile(listOf(source("必须保留原文")), "", budget)
            assertEquals("", result.text)
            assertEquals(0, result.kept)
        }
    }

    @Test
    fun `source ids line numbers roles and conflicting claims remain quotations`() {
        val sources = listOf(
            source("必须修改 ChatService.kt\n\n计划运行测试", "request", "user"),
            source("测试失败；未完成\n已完成只是先前的错误报告", "answer", "assistant"),
        )
        val result = TomcAgentCompiler.compile(sources, "测试", 2000)
        assertTrue(result.text.contains("[source=request:L3 role=user] 计划运行测试"))
        assertTrue(result.text.contains("[source=answer:L1 role=assistant] 测试失败；未完成"))
        assertTrue(result.text.contains("已完成只是先前的错误报告"))
        assertEquals(4, result.kept)
        assertEquals(0, result.omitted)
        assertFalse(result.text.contains("verified|"))
        assertFalse(result.text.contains("[CLMA_TOMC_LEDGER"))
    }

    @Test
    fun `oversized record is omitted rather than clipping a sha or error`() {
        val oversized = "error " + "x".repeat(10000) + " 25218fffb9914b0ff8e853c6e26cb1353dfce529"
        val result = TomcAgentCompiler.compile(listOf(source(oversized + "\n最新要求：不要部署")), "", 300)
        assertEquals(1, result.kept)
        assertEquals(1, result.omitted)
        assertTrue(result.text.contains("最新要求：不要部署"))
        assertFalse(result.text.contains("25218fff"))
        assertFalse(result.text.contains("error x"))
    }

    @Test
    fun `output never exceeds the declared character estimate budget`() {
        val sources = (1..30).map {
            source("必须保留第${it}条约束 /workspace/file$it.kt\n测试失败，不代表成功 😀", "m$it")
        }
        for (budget in 1..1500 step 7) {
            val result = TomcAgentCompiler.compile(sources, "file", budget)
            assertTrue("budget=$budget", TomcAgentCompiler.estimate(result.text) <= budget)
            assertEquals(60, result.kept + result.omitted)
        }
    }

    @Test
    fun `recompilation from the same originals is deterministic and does not nest memory`() {
        val sources = (1..20).map { source("目标：修改文件$it.kt，未运行测试", "m$it") }
        val first = TomcAgentCompiler.compile(sources, "修改", 600)
        repeat(5) { assertEquals(first, TomcAgentCompiler.compile(sources, "修改", 600)) }
        assertEquals(1, Regex("\\[TOMC2 Android source memory]").findAll(first.text).count())
    }

    @Test
    fun `unmatched input retains recent complete records`() {
        val sources = (1..20).map { source("ordinary sentence number $it " + "x".repeat(50), "m$it") }
        val result = TomcAgentCompiler.compile(sources, "absent", 250)
        assertTrue(result.text.contains("[source=m20:L1 role=user]"))
        assertFalse(result.text.contains("[source=m1:L1 role=user]"))
    }

    @Test
    fun `unicode estimate counts cjk and surrogate pairs conservatively`() {
        assertEquals(4, TomcAgentCompiler.estimate("中文😀"))
        assertEquals(4, TomcAgentCompiler.estimate("abcdefghij"))
        assertEquals(0, TomcAgentCompiler.estimate(""))
    }
}
