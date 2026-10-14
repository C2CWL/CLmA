package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-05 — the LOCAL palette, on a bare JVM.
 *
 * Two things are being pinned here.
 *
 *  1. **Provenance.** Every entry the palette publishes must come back from the shared
 *     [ToolCatalog] carrying [ToolCatalogSource.LOCAL] — the acceptance criterion for this card
 *     is literally "the palette lists the local directory and hands back source=LOCAL", so it is
 *     asserted directly rather than inferred from the UI.
 *  2. **One ranking.** The palette must not grow its own scorer: a query answered by
 *     [LocalToolPalette.search] has to agree with [ToolCatalog.search] hit for hit, including
 *     the 8-hit cap, and [LocalToolPalette.matchAll] must be the same list without the cap.
 *
 * Group attribution, the summary shape and the empty/duplicate edges are covered alongside.
 */
class LocalToolPaletteTest {

    private fun tool(name: String, description: String = "summary for $name"): Tool = Tool(
        name = name,
        description = description,
        execute = { emptyList<UIMessagePart>() },
    )

    private fun inventory(
        vararg groups: Pair<LocalToolOption, List<String>>,
    ): List<LocalToolInventory> = groups.map { (group, names) ->
        LocalToolInventory(group = group, tools = names.map { tool(it) })
    }

    // ------------------------------------------------------------------ build / browse

    @Test
    fun `every tool lands in the palette once, in inventory order`() {
        val palette = LocalToolPalette.build(
            inventory(
                LocalToolOption.Ssh to listOf("ssh_exec", "ssh_upload"),
                LocalToolOption.CronJobs to listOf("cron_create"),
            )
        )
        assertEquals(listOf("ssh_exec", "ssh_upload", "cron_create"), palette.hits.map { it.name })
        assertEquals(3, palette.size)
    }

    @Test
    fun `a name offered by two groups is attributed to the first group`() {
        val palette = LocalToolPalette.build(
            inventory(
                LocalToolOption.Ssh to listOf("shared_tool"),
                LocalToolOption.CronJobs to listOf("shared_tool"),
            )
        )
        assertEquals(1, palette.size)
        assertEquals(LocalToolOption.Ssh, palette.hit("shared_tool")?.group)
        assertEquals(listOf(LocalToolOption.Ssh), palette.groups)
    }

    @Test
    fun `web_fetch and web_extract are not attributed to any group`() {
        // The factory appends these to every single-group query. Attributing them to the
        // first group would still show them under one category; dropping them shows them
        // under none, which is where Search settings owns them.
        val palette = LocalToolPalette.build(
            inventory(
                LocalToolOption.JavascriptEngine to listOf(
                    "javascript", "web_fetch", "web_extract", "check_app_updates",
                ),
                LocalToolOption.Battery to listOf("battery", "web_fetch", "web_extract"),
            )
        )
        assertEquals(listOf("javascript", "battery"), palette.hits.map { it.name })
        assertNull(palette.group("web_fetch"))
        assertNull(palette.group("web_extract"))
        assertNull(palette.group("check_app_updates"))
        assertEquals(
            listOf(LocalToolOption.JavascriptEngine, LocalToolOption.Battery),
            palette.groups,
        )
    }

    @Test
    fun `hits carry the LOCAL source`() {
        val palette = LocalToolPalette.build(inventory(LocalToolOption.Files to listOf("read_file")))
        assertEquals(ToolCatalogSource.LOCAL, palette.hits.single().source)
        assertEquals("LOCAL", palette.hits.single().source.name)
        assertEquals(ToolCatalogSource.LOCAL, palette.search("read_file").single().source)
        assertEquals(ToolCatalogSource.LOCAL, palette.entry("read_file")?.source)
    }

    @Test
    fun `blank tool names are skipped`() {
        val palette = LocalToolPalette.build(
            inventory(LocalToolOption.Files to listOf("", "  ", "read_file"))
        )
        assertEquals(listOf("read_file"), palette.hits.map { it.name })
        assertNull(palette.group(""))
    }

    @Test
    fun `groups are deduplicated in first-appearance order`() {
        val palette = LocalToolPalette.build(
            inventory(
                LocalToolOption.Ssh to listOf("ssh_exec"),
                LocalToolOption.Files to emptyList(),
                LocalToolOption.Ssh to listOf("ssh_download"),
                LocalToolOption.Termux to listOf("termux_run_command"),
            )
        )
        assertEquals(listOf(LocalToolOption.Ssh, LocalToolOption.Termux), palette.groups)
        assertEquals(3, palette.size)
    }

    @Test
    fun `an empty inventory yields an empty palette`() {
        val palette = LocalToolPalette.build(emptyList())
        assertEquals(0, palette.size)
        assertTrue(palette.groups.isEmpty())
        assertTrue(palette.search("anything").isEmpty())
        assertNull(palette.hit("read_file"))
        assertNull(palette.entry("read_file"))
        assertNull(palette.group("read_file"))
    }

    @Test
    fun `the catalogue entry keeps the live tool`() {
        val live = tool("read_file", description = "Read a file from disk.")
        val palette = LocalToolPalette.build(
            listOf(LocalToolInventory(LocalToolOption.Files, listOf(live)))
        )
        val entry = palette.entry("read_file")
        assertNotNull(entry)
        assertSame(live, entry!!.tool)
        assertEquals(ToolCatalogSource.LOCAL, entry.source)
    }

    // ------------------------------------------------------------------ summaries

    @Test
    fun `summarize takes the first non-blank line and collapses whitespace`() {
        val described = tool(
            "weird",
            description = "\n    First    line of the description\n\n    Second line\n",
        )
        assertEquals("First line of the description", LocalToolPalette.summarize(described))
        assertEquals(
            "First line of the description",
            LocalToolPalette.build(
                listOf(LocalToolInventory(LocalToolOption.Files, listOf(described)))
            ).hits.single().summary,
        )
    }

    @Test
    fun `summarize falls back when there is no description`() {
        assertEquals(LOCAL_TOOL_NO_DESCRIPTION, LocalToolPalette.summarize(tool("bare", description = "")))
        assertEquals(LOCAL_TOOL_NO_DESCRIPTION, LocalToolPalette.summarize(tool("blank", description = "   \n  ")))
    }

    // ------------------------------------------------------------------ search

    @Test
    fun `search returns nothing for a blank query`() {
        val palette = LocalToolPalette.build(inventory(LocalToolOption.Ssh to listOf("ssh_exec")))
        assertTrue(palette.search("").isEmpty())
        assertTrue(palette.search("   ").isEmpty())
        assertTrue(palette.matchAll("").isEmpty())
    }

    @Test
    fun `search reuses the catalogue ranking`() {
        val palette = LocalToolPalette.build(
            inventory(
                LocalToolOption.Ssh to listOf("ssh_exec", "ssh_exec_saved"),
                LocalToolOption.Files to listOf("read_file"),
            )
        )
        val hits = palette.search("ssh_exec")
        assertEquals("ssh_exec", hits.first().name)
        assertTrue(hits.map { it.name }.contains("ssh_exec_saved"))
    }

    @Test
    fun `search hits keep the owning group`() {
        val palette = LocalToolPalette.build(
            inventory(
                LocalToolOption.Ssh to listOf("ssh_exec"),
                LocalToolOption.Files to listOf("read_file"),
            )
        )
        val hit = palette.search("read_file").single()
        assertEquals(LocalToolOption.Files, hit.group)
        assertEquals(LocalToolOption.Files, palette.group("read_file"))
        assertEquals(LocalToolOption.Ssh, palette.group("ssh_exec"))
        assertNull(palette.group("nope"))
    }

    @Test
    fun `search stays capped at the documented maximum`() {
        val palette = LocalToolPalette.build(
            inventory(LocalToolOption.Files to (1..20).map { "file_tool_$it" })
        )
        assertEquals(TOOL_CATALOG_MAX_SEARCH_RESULTS, palette.search("file").size)
        assertEquals(20, palette.matchAll("file").size)
    }

    @Test
    fun `matchAll is the same ranking without the cap`() {
        val palette = LocalToolPalette.build(
            inventory(LocalToolOption.Files to (1..12).map { "file_tool_$it" })
        )
        val capped = palette.search("file")
        val all = palette.matchAll("file")
        assertEquals(all.take(TOOL_CATALOG_MAX_SEARCH_RESULTS).map { it.name }, capped.map { it.name })
        assertTrue(all.size > capped.size)
        // Deterministic tie-break: equal scores come back by name, never by insertion order.
        assertEquals(all.map { it.name }.sorted(), all.map { it.name })
    }

    @Test
    fun `hit lookup is null for an unknown name`() {
        val palette = LocalToolPalette.build(inventory(LocalToolOption.Files to listOf("read_file")))
        assertNull(palette.hit("write_file"))
        assertEquals("read_file", palette.hit("read_file")?.name)
        assertEquals("summary for read_file", palette.hit("read_file")?.summary)
    }
}
