package com.clma.app.ui.pages.setting

import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.ui.pages.setting.SettingsSearchTestStrings.chinese
import com.clma.app.ui.pages.setting.SettingsSearchTestStrings.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real queries against real strings.xml text — the regression the user hit was "工作区" →
 * "没有结果" because no extension sub-page was indexed at all.
 */
class SettingsSearchQueryTest {

    private fun search(
        query: String,
        content: SettingsSearchUserContent = SettingsSearchUserContent(),
        text: (Int) -> String = chinese,
        fallback: ((Int) -> String)? = english,
    ): List<SettingsSearchItem> {
        val index = settingsSearchIndex(developerMode = false)
        val items = index + settingsSearchDynamicEntries(content, index)
        return rankSettingsSearch(query, items, text, fallback)
    }

    private fun assertTop(query: String, expected: Screen, results: List<SettingsSearchItem> = search(query)) {
        assertTrue("'$query' returned nothing", results.isNotEmpty())
        assertEquals("top result for '$query'", expected, results.first().route)
    }

    @Test
    fun `workspace in chinese opens the workspaces page`() {
        assertTop("工作区", Screen.Workspaces)
    }

    @Test
    fun `extension sub pages are all found`() {
        assertTop("快捷消息", Screen.QuickMessages)
        assertTop("Agent Skills", Screen.Skills)
        assertTop("提示词注入", Screen.Prompts())
        assertTop("模式注入", Screen.Prompts(initialTab = 0))
    }

    @Test
    fun `lorebook opens the prompts page on the lorebook tab`() {
        assertTop("世界书", Screen.Prompts(initialTab = 1))
    }

    @Test
    fun `workspace is a hub row now`() {
        val top = search("工作区").first() as SettingsSearchEntry
        assertEquals(emptyList<Int>(), top.breadcrumbRes)
        assertEquals(R.string.setting_hub_group_connections, top.groupRes)
    }

    @Test
    fun `moved rows are found on their new pages`() {
        assertTop("总时间预算", Screen.SettingRuntime)
        assertTop("错误提示自动消失", Screen.SettingPreferencesGeneral)
        assertTop("AMOLED", Screen.SettingColorMode)
        assertTop("弹出菜单样式", Screen.SettingPreferencesTheme)
    }

    @Test
    fun `termux rows are found and open the termux page`() {
        assertTop("Termux 超时", Screen.SettingTermux)
        assertTop("stdout", Screen.SettingTermux)
        assertTop("allow-external-apps", Screen.SettingTermux)
    }

    @Test
    fun `english terms match while the ui is chinese`() {
        assertTop("Workspace", Screen.Workspaces)
        assertTop("Quick Messages", Screen.QuickMessages)
        assertTop("Lorebook", Screen.Prompts(initialTab = 1))
    }

    @Test
    fun `english ui alone finds the same pages`() {
        assertTop("Workspace", Screen.Workspaces, search("Workspace", text = english, fallback = null))
        assertTop("workspace", Screen.Workspaces, search("workspace", text = english, fallback = null))
    }

    @Test
    fun `tabs deep link`() {
        assertTop("WebDAV", Screen.Backup(initialTab = 1))
        assertTop("S3 Bucket", Screen.Backup(initialTab = 2))
        assertTop("备份提醒", Screen.Backup(initialTab = 3))
        assertTop("语音识别", Screen.SettingSpeech(initialTab = 1))
        assertTop("标题生成", Screen.SettingModels(initialTab = 1))
    }

    @Test
    fun `other formerly missing pages are found`() {
        assertTop("单任务超时", Screen.SettingBrowser)
        assertTop("自定义主题", Screen.SettingTheme)
        assertTop("审批模式", Screen.SettingToolApprovals)
        assertTop("派发姿态", Screen.SettingSubAgents)
    }

    @Test
    fun `multi token query still needs every token`() {
        assertTrue(search("工作区 zzzz-no-such-thing").isEmpty())
        assertTrue(search("   ").isEmpty())
    }

    @Test
    fun `user content is searchable and opens its detail page`() {
        val content = SettingsSearchUserContent(
            workspaces = listOf(SettingsSearchUserItem("ws-1", "论文项目", "/data/thesis")),
            skills = listOf(SettingsSearchUserItem("pdf-tools", "pdf-tools", "Read PDFs")),
            quickMessages = listOf(SettingsSearchUserItem("q1", "早安问候", "早上好！")),
            lorebooks = listOf(SettingsSearchUserItem("l1", "中土世界", null)),
            modeInjections = listOf(SettingsSearchUserItem("m1", "学习模式", null)),
            providers = listOf(SettingsSearchUserItem("p1", "My Relay", null)),
            searchServices = listOf(SettingsSearchUserItem("s1", "Tavily", null)),
            assistants = listOf(SettingsSearchUserItem("a1", "翻译助手", null)),
            mcpServers = listOf(SettingsSearchUserItem("mcp1", "github-mcp", null)),
            ttsProviders = listOf(SettingsSearchUserItem("t1", "MiniMax TTS", null)),
            asrProviders = listOf(SettingsSearchUserItem("r1", "Whisper", null)),
        )
        assertTop("论文项目", Screen.WorkspaceDetail("ws-1"), search("论文项目", content = content))
        assertTop("pdf-tools", Screen.SkillDetail("pdf-tools"), search("pdf-tools", content = content))
        assertTop("早安", Screen.QuickMessages, search("早安", content = content))
        assertTop("中土", Screen.Prompts(initialTab = 1), search("中土", content = content))
        assertTop("学习模式", Screen.Prompts(initialTab = 0), search("学习模式", content = content))
        assertTop("My Relay", Screen.SettingProviderDetail("p1"), search("My Relay", content = content))
        assertTop("Tavily", Screen.SettingSearchDetail("s1"), search("Tavily", content = content))
        assertTop("翻译助手", Screen.AssistantDetail("a1"), search("翻译助手", content = content))
        assertTop("github-mcp", Screen.SettingMcp, search("github-mcp", content = content))
        assertTop("MiniMax", Screen.SettingSpeech(initialTab = 0), search("MiniMax", content = content))
        assertTop("Whisper", Screen.SettingSpeech(initialTab = 1), search("Whisper", content = content))

        // "工作区" still puts the page first, the user's workspace right after it.
        val results = search("工作区", content = content)
        assertEquals(Screen.Workspaces, results.first().route)
        assertTrue(results.any { it.route == Screen.WorkspaceDetail("ws-1") })
    }
}
