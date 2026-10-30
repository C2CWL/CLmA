package com.clma.app.ui.pages.setting

import com.clma.app.R
import com.clma.app.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchDynamicTest {

    private val index = settingsSearchIndex(developerMode = false)

    @Test
    fun `every kind is anchored to its list page`() {
        val one = listOf(SettingsSearchUserItem("id", "name", "detail"))
        val content = SettingsSearchUserContent(
            workspaces = one, skills = one, quickMessages = one, modeInjections = one,
            lorebooks = one, mcpServers = one, providers = one, searchServices = one,
            assistants = one, ttsProviders = one, asrProviders = one,
        )
        val entries = settingsSearchDynamicEntries(content, index)
        // One per kind, plus one result per assistant sub-page.
        assertEquals(11 + assistantSearchSubPages.size, entries.size)
        entries.forEach { entry ->
            assertTrue("empty breadcrumb for ${entry.route}", entry.breadcrumbRes.isNotEmpty())
            assertTrue(entry.groupRes != 0)
        }
    }

    @Test
    fun `workspace breadcrumb is the hub title`() {
        val entries = settingsSearchDynamicEntries(
            SettingsSearchUserContent(workspaces = listOf(SettingsSearchUserItem("w", "Thesis"))),
            index,
        )
        val entry = entries.single()
        assertEquals(Screen.WorkspaceDetail("w"), entry.route)
        // Workspaces is a hub row in the tools group (extensions intermediate page is gone).
        assertEquals(listOf(R.string.extensions_page_workspace), entry.breadcrumbRes)
        assertEquals(R.string.setting_hub_group_connections, entry.groupRes)
    }

    @Test
    fun `lorebooks open the lorebook tab`() {
        val entry = settingsSearchDynamicEntries(
            SettingsSearchUserContent(lorebooks = listOf(SettingsSearchUserItem("l", "Book"))),
            index,
        ).single()
        assertEquals(Screen.Prompts(initialTab = 1), entry.route)
        assertEquals(R.string.prompt_page_lorebook_tab, entry.breadcrumbRes.last())
    }

    @Test
    fun `each assistant sub page opens that assistant`() {
        val entries = settingsSearchDynamicEntries(
            SettingsSearchUserContent(assistants = listOf(SettingsSearchUserItem("a1", "翻译助手"))),
            index,
        )
        val byRoute = entries.associateBy { it.route }
        assertEquals(1 + assistantSearchSubPages.size, entries.size)
        assertEquals(Screen.AssistantDetail("a1"), entries.first().route)
        for (page in assistantSearchSubPages) {
            val entry = byRoute[page.route("a1")]
            assertTrue("missing ${page.route("a1")}", entry != null)
            assertEquals(page.titleRes, entry!!.titleRes)
            assertEquals("翻译助手", entry.subtitle)
            assertTrue(entry.breadcrumbRes.isNotEmpty())
        }
    }

    @Test
    fun `blank names are skipped`() {
        val entries = settingsSearchDynamicEntries(
            SettingsSearchUserContent(assistants = listOf(SettingsSearchUserItem("a", "  "))),
            index,
        )
        assertTrue(entries.isEmpty())
    }
}
