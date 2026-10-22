package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-02 — `LocalToolFilter` contract, exercised on a bare JVM (no Android, no Compose).
 *
 * The card's red line is "empty set = byte-identical surface"; the first test pins that down
 * as literally "no mutation, same instance".
 */
class LocalToolsFilterTest {

    private fun tool(name: String) = Tool(
        name = name,
        description = "test tool $name",
        execute = { emptyList() },
    )

    private fun surface() = mutableListOf(tool("a"), tool("b"), tool("c"))

    @Test
    fun `empty deny list is a strict no-op`() {
        val tools = surface()
        val out = LocalToolFilter.removeDisabled(tools, emptySet())
        assertSame(tools, out)
        assertEquals(listOf("a", "b", "c"), out.map { it.name })
    }

    @Test
    fun `a disabled tool is dropped`() {
        val tools = surface()
        LocalToolFilter.removeDisabled(tools, setOf("b"))
        assertEquals(listOf("a", "c"), tools.map { it.name })
    }

    @Test
    fun `order of the survivors is preserved`() {
        val tools = surface()
        LocalToolFilter.removeDisabled(tools, setOf("a", "c"))
        assertEquals(listOf("b"), tools.map { it.name })
    }

    @Test
    fun `unknown names are ignored`() {
        val tools = surface()
        LocalToolFilter.removeDisabled(tools, setOf("nope", "zzz"))
        assertEquals(listOf("a", "b", "c"), tools.map { it.name })
    }

    @Test
    fun `disabling every tool empties the surface`() {
        val tools = surface()
        LocalToolFilter.removeDisabled(tools, setOf("a", "b", "c"))
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `non-disabled tools keep their identity`() {
        val tools = surface()
        val keepFirst = tools[0]
        val keepLast = tools[2]
        LocalToolFilter.removeDisabled(tools, setOf("b"))
        assertSame(keepFirst, tools[0])
        assertSame(keepLast, tools[1])
    }

    @Test
    fun `ungrouped names are not a per-assistant opt-out`() {
        assertSame(emptySet<String>(), LocalToolFilter.applicableDisabledNames(emptySet()))
        assertEquals(
            setOf("read_file"),
            LocalToolFilter.applicableDisabledNames(
                setOf("read_file", "web_fetch", "web_extract", "check_app_updates"),
            ),
        )
        assertEquals(
            emptySet<String>(),
            LocalToolFilter.applicableDisabledNames(LocalToolFilter.UNGROUPED_TOOL_NAMES),
        )
    }

    @Test
    fun `ungrouped names survive a deny list that names them`() {
        val tools = mutableListOf(
            tool("read_file"),
            tool("web_fetch"),
            tool("web_extract"),
            tool("check_app_updates"),
        )
        LocalToolFilter.removeDisabled(
            tools,
            LocalToolFilter.applicableDisabledNames(setOf("web_fetch", "web_extract", "read_file")),
        )
        assertEquals(listOf("web_fetch", "web_extract", "check_app_updates"), tools.map { it.name })
    }
}
