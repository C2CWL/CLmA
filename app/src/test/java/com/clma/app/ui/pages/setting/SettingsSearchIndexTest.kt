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
        val classicGroups = setOf(
            R.string.setting_page_group_appearance,
            R.string.setting_page_group_models,
            R.string.setting_page_group_assistant,
            R.string.setting_page_group_device,
            R.string.setting_page_group_connections,
            R.string.setting_page_group_data,
            R.string.setting_page_group_about,
            R.string.settings,
        )
        val reorganizedGroups = setOf(
            R.string.setting_page_group_appearance_reorg,
            R.string.setting_page_group_models_reorg,
            R.string.setting_page_group_assistant,
            R.string.setting_page_group_device,
            R.string.setting_page_group_connections_reorg,
            R.string.setting_page_group_data,
            R.string.setting_page_group_about,
            R.string.settings,
        )
        settingsSearchIndex(developerMode = true, hubStyle = com.clma.app.data.datastore.SettingsHubStyle.CLASSIC)
            .forEach { entry ->
                assertTrue("unexpected classic group for ${entry.route}", classicGroups.contains(entry.groupRes))
            }
        settingsSearchIndex(developerMode = true, hubStyle = com.clma.app.data.datastore.SettingsHubStyle.REORGANIZED)
            .forEach { entry ->
                assertTrue("unexpected reorganized group for ${entry.route}", reorganizedGroups.contains(entry.groupRes))
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
    fun `the theme page answers to the colour-mode alias`() {
        val entry = settingsSearchIndex(developerMode = false)
            .single { it.route == Screen.SettingPreferencesTheme && it.parentTitleRes == null }
        assertTrue(entry.altTitles.contains(R.string.setting_page_color_mode))
    }

    @Test
    fun `nested ui rows point at the ui preferences page`() {
        val classicNested = settingsSearchIndex(
            developerMode = false,
            hubStyle = com.clma.app.data.datastore.SettingsHubStyle.CLASSIC,
        ).filter { it.route == Screen.SettingPreferencesUI && it.parentTitleRes != null }
        assertTrue(classicNested.size > 10)
        assertTrue(classicNested.any { it.titleRes == R.string.setting_display_page_popup_menu_style_title })
        assertTrue(classicNested.any { it.titleRes == R.string.setting_page_settings_hub_style_title })
        classicNested.forEach { entry ->
            assertEquals(R.string.setting_page_preferences_ui, entry.parentTitleRes)
        }

        val reorgNested = settingsSearchIndex(
            developerMode = false,
            hubStyle = com.clma.app.data.datastore.SettingsHubStyle.REORGANIZED,
        ).filter { it.route == Screen.SettingPreferencesUI && it.parentTitleRes != null }
        reorgNested.forEach { entry ->
            assertEquals(R.string.setting_page_hub_chat_display, entry.parentTitleRes)
        }
    }

    @Test
    fun `classic and reorganized hubs share the same routes`() {
        fun hubRoutes(style: com.clma.app.data.datastore.SettingsHubStyle) =
            settingsSearchIndex(developerMode = false, hubStyle = style)
                .filter { it.parentTitleRes == null }
                .map { it.route }
                .toSet()
        assertEquals(
            hubRoutes(com.clma.app.data.datastore.SettingsHubStyle.CLASSIC),
            hubRoutes(com.clma.app.data.datastore.SettingsHubStyle.REORGANIZED),
        )
    }

    @Test
    fun `reorganized moves network under connections group`() {
        val entry = settingsSearchIndex(
            developerMode = false,
            hubStyle = com.clma.app.data.datastore.SettingsHubStyle.REORGANIZED,
        ).single { it.route == Screen.SettingPreferencesNetwork && it.parentTitleRes == null }
        assertEquals(R.string.setting_page_group_connections_reorg, entry.groupRes)
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
    fun `extension sub pages sit under the extensions page`() {
        val entries = settingsSearchIndex(developerMode = false)
        listOf(Screen.Workspaces, Screen.QuickMessages, Screen.Prompts(), Screen.Skills).forEach { route ->
            val page = entries.first { it.route == route }
            assertEquals("parent of $route", R.string.setting_page_extensions, page.parentTitleRes)
            assertEquals(R.string.setting_page_group_assistant, page.groupRes)
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
    fun `nested rows keep the other hub style name as a search alias`() {
        val classicUiRow = settingsSearchIndex(
            developerMode = false,
            hubStyle = com.clma.app.data.datastore.SettingsHubStyle.CLASSIC,
        ).first { it.route == Screen.SettingPreferencesUI && it.parentTitleRes != null }
        assertTrue(classicUiRow.altTitles.contains(R.string.setting_page_hub_chat_display))
    }
}
