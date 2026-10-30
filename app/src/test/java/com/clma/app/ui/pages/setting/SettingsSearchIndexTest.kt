package com.clma.app.ui.pages.setting

import com.clma.app.R
import com.clma.app.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {

    @Test
    fun `no duplicate hub routes with developer mode off`() {
        val hubs = settingsSearchIndex(developerMode = false).filter { it.parentTitleRes == null }
        val routes = hubs.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `no duplicate hub routes with developer mode on`() {
        val hubs = settingsSearchIndex(developerMode = true).filter { it.parentTitleRes == null }
        val routes = hubs.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `nested uniqueness is route title and parent`() {
        val keys = settingsSearchIndex(developerMode = true).map {
            Triple(it.route, it.titleRes, it.parentTitleRes)
        }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `every entry has non-zero resource ids`() {
        val entries = settingsSearchIndex(developerMode = true)
        entries.forEach { entry ->
            assertTrue("titleRes must be non-zero for ${entry.route}", entry.titleRes != 0)
            assertTrue("groupRes must be non-zero for ${entry.route}", entry.groupRes != 0)
            entry.descriptionRes?.let {
                assertTrue("descriptionRes must be non-zero for ${entry.route}", it != 0)
            }
            entry.parentTitleRes?.let {
                assertTrue("parentTitleRes must be non-zero for ${entry.route}", it != 0)
            }
        }
    }

    @Test
    fun `developer row only present when the flag is set`() {
        val withoutDeveloper = settingsSearchIndex(developerMode = false)
        val withDeveloper = settingsSearchIndex(developerMode = true)

        assertFalse(withoutDeveloper.any { it.route == Screen.Developer })
        assertTrue(withDeveloper.any { it.route == Screen.Developer })
        assertEquals(withoutDeveloper.size + 1, withDeveloper.size)
    }

    @Test
    fun `every row belongs to a known hub group`() {
        val groups = settingsHubSections.map { it.titleRes }.toSet() + R.string.settings
        settingsSearchIndex(developerMode = true).forEach { entry ->
            assertTrue("unexpected group for ${entry.route}", groups.contains(entry.groupRes))
        }
    }

    @Test
    fun `search-only aliases are non-zero`() {
        settingsSearchIndex(developerMode = false).forEach { entry ->
            entry.altTitles.forEach { alias ->
                assertTrue("alias must be non-zero for ${entry.route}", alias != 0)
            }
        }
    }

    @Test
    fun `the colour mode page answers to the colour-mode alias`() {
        val entry = settingsSearchIndex(developerMode = false)
            .single { it.route == Screen.SettingColorMode && it.parentTitleRes == null }
        assertTrue(entry.altTitles.contains(R.string.setting_page_color_mode))
    }

    @Test
    fun `message display rows point at the message display page`() {
        val nested = settingsSearchIndex(developerMode = false)
            .filter { it.route == Screen.SettingPreferencesUI && it.parentTitleRes != null }
        assertTrue(nested.size > 10)
        nested.forEach { entry ->
            assertEquals(R.string.setting_hub_message_display, entry.parentTitleRes)
        }
        assertFalse(nested.any { it.titleRes == R.string.setting_display_page_popup_menu_style_title })
    }

    @Test
    fun `moved rows follow their new pages`() {
        val entries = settingsSearchIndex(developerMode = false)
        fun routeOf(title: Int) = entries.single { it.titleRes == title && it.parentTitleRes != null }.route
        assertEquals(Screen.SettingPreferencesTheme, routeOf(R.string.setting_display_page_popup_menu_style_title))
        assertEquals(Screen.SettingPreferencesTheme, routeOf(R.string.setting_display_page_app_icon_title))
        assertEquals(Screen.SettingColorMode, routeOf(R.string.setting_display_page_amoled_dark_mode_title))
        assertEquals(Screen.SettingFonts, routeOf(R.string.setting_display_page_ui_font_scale_title))
        assertEquals(Screen.SettingChatLayout, routeOf(R.string.setting_display_page_clean_layout_title))
        assertEquals(Screen.SettingPreferencesGeneral, routeOf(R.string.error_auto_dismiss_title))
        assertEquals(Screen.SettingRuntime, routeOf(R.string.setting_model_page_turn_budget))
        assertEquals(Screen.SettingRuntime, routeOf(R.string.setting_model_page_max_tool_steps))
        assertEquals(Screen.SettingRuntime, routeOf(R.string.setting_model_page_enable_auto_compaction))
        assertEquals(Screen.SettingRuntime, routeOf(R.string.setting_display_page_parse_text_tool_calls_title))
    }

    @Test
    fun `hub rows match the hub catalog`() {
        val hubRoutes = settingsSearchIndex(developerMode = false)
            .filter { it.parentTitleRes == null }
            .map { it.route }
            .toSet()
        val catalogRoutes = settingsHubSections.flatMap { section ->
            section.items.mapNotNull { settingsHubItems.getValue(it).route }
        }.toSet()
        assertEquals(catalogRoutes, hubRoutes)
    }

    @Test
    fun `six hub groups own every setting exactly once`() {
        assertEquals(6, settingsHubSections.size)
        val items = settingsHubSections.flatMap { it.items }
        assertEquals(SettingsHubItemId.entries.toSet(), items.toSet())
        assertEquals(items.size, items.distinct().size)
    }

    @Test
    fun `assistant and context live together while secrets belong to privacy`() {
        fun groupOf(id: SettingsHubItemId) = settingsHubSections.single { id in it.items }.id
        assertEquals(SettingsHubSectionId.CHAT, groupOf(SettingsHubItemId.ASSISTANT))
        assertEquals(SettingsHubSectionId.CHAT, groupOf(SettingsHubItemId.RUNTIME))
        assertEquals(SettingsHubSectionId.DATA, groupOf(SettingsHubItemId.SECRETS))
        assertEquals(SettingsHubSectionId.ABOUT, groupOf(SettingsHubItemId.LOGS))
    }

    @Test
    fun `network sits in the connections group`() {
        val entry = settingsSearchIndex(developerMode = false)
            .single { it.route == Screen.SettingPreferencesNetwork && it.parentTitleRes == null }
        assertEquals(R.string.setting_hub_group_connections, entry.groupRes)
    }

    @Test
    fun `hub rows have no parent title`() {
        settingsSearchIndex(developerMode = false)
            .filter { it.parentTitleRes == null }
            .forEach { entry ->
                assertNull(entry.parentTitleRes)
            }
    }

    @Test
    fun `edge bar nested colour is indexed`() {
        val entry = settingsSearchIndex(developerMode = false).single {
            it.route == Screen.SettingFloatingBall &&
                it.titleRes == R.string.setting_floating_ball_color_title
        }
        assertNotNull(entry.parentTitleRes)
        // Nested rows name their parent by the hub row the user tapped.
        assertEquals(R.string.setting_page_floating_ball, entry.parentTitleRes)
    }

    @Test
    fun `breadcrumbs and aliases are non-zero`() {
        settingsSearchIndex(developerMode = true).forEach { entry ->
            entry.breadcrumbRes.forEach { assertTrue("breadcrumb id must be non-zero for ${entry.route}", it != 0) }
        }
    }

    @Test
    fun `former extension pages are hub rows that answer to the old page name`() {
        val entries = settingsSearchIndex(developerMode = false)
        listOf(Screen.Workspaces, Screen.QuickMessages, Screen.Prompts(), Screen.Skills).forEach { route ->
            val page = entries.single { it.route == route && it.parentTitleRes == null }
            assertTrue("alias of $route", page.altTitles.contains(R.string.setting_page_extensions))
        }
    }

    @Test
    fun `tab rows carry the tab route and the tab in their path`() {
        val entries = settingsSearchIndex(developerMode = false)
        val s3Bucket = entries.single { it.titleRes == R.string.backup_page_s3_bucket }
        assertEquals(Screen.Backup(initialTab = 2), s3Bucket.route)
        assertEquals(R.string.backup_page_s3_backup, s3Bucket.parentTitleRes)
        // The same title in two tabs stays two rows.
        assertEquals(3, entries.count { it.titleRes == R.string.backup_page_backup_items })
    }

    @Test
    fun `renamed hub pages answer to their former names`() {
        val entries = settingsSearchIndex(developerMode = false).filter { it.parentTitleRes == null }
        fun aliases(route: Screen) = entries.single { it.route == route }.altTitles
        assertTrue(aliases(Screen.SettingPreferencesTheme).contains(R.string.setting_page_preferences_theme))
        assertTrue(aliases(Screen.SettingPreferencesUI).contains(R.string.setting_page_preferences_ui))
        assertTrue(aliases(Screen.SettingPreferencesGeneral).contains(R.string.setting_page_preferences_general))
        assertTrue(aliases(Screen.SettingModels()).contains(R.string.setting_model_page_title))
    }
}
