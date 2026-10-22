package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the split/merge contract between the two writers of a playbook document: the agent's
 * prose (via `memory_write`) and the host's delimited observation block. If this drifts, either
 * a rewrite silently eats the agent's notes or a duplicate line accumulates forever.
 */
class AppPlaybookFileTest {

    @Test
    fun `a document without markers is all prose`() {
        val (prose, lines) = AppPlaybookFile.split("WeChat: 通讯录 → 搜索 → 发送")
        assertEquals("WeChat: 通讯录 → 搜索 → 发送", prose)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `a malformed block degrades to prose instead of losing content`() {
        val halfOpen = "# App\n\n${AppPlaybookFile.AUTO_BEGIN}\n- orphan\n"
        val (prose, lines) = AppPlaybookFile.split(halfOpen)
        assertTrue(prose.contains("orphan"))
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `render then split round trips prose and lines`() {
        val prose = "# com.tencent.mm\n\n- Route: 通讯录 → 搜索"
        val lines = listOf("- LauncherUI｜click text=\"搜索\"｜ok", "- LauncherUI｜miss text=\"Send\"")

        val rendered = AppPlaybookFile.render(prose, lines)
        val (proseAgain, linesAgain) = AppPlaybookFile.split(rendered)

        assertEquals(prose, proseAgain)
        assertEquals(lines, linesAgain)
    }

    @Test
    fun `render is byte stable on a repeated rewrite`() {
        val once = AppPlaybookFile.render("prose", listOf("- a", "- b"))
        val split = AppPlaybookFile.split(once)
        val twice = AppPlaybookFile.render(split.first, split.second)
        assertEquals(once, twice)
        assertTrue(once.endsWith("\n"))
    }

    @Test
    fun `an empty document renders empty and never throws`() {
        assertEquals("", AppPlaybookFile.render("", emptyList()).trim())
        assertEquals("", AppPlaybookFile.render("   ", emptyList()).trim())
    }

    @Test
    fun `merge dedupes keeping the newest occurrence and caps the length`() {
        assertEquals(
            listOf("- a", "- b"),
            AppPlaybookFile.mergeLines(listOf("- a", "- b"), listOf("- b")),
        )

        val many = (1..AppPlaybookFile.MAX_AUTO_LINES + 10).map { "- line-$it" }
        val merged = AppPlaybookFile.mergeLines(emptyList(), many)
        assertEquals(AppPlaybookFile.MAX_AUTO_LINES, merged.size)
        // Newest win: the tail is kept, the head is dropped.
        assertEquals("- line-${AppPlaybookFile.MAX_AUTO_LINES + 10}", merged.last())
    }
}
