package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {

    @Test
    fun `no duplicate routes with developer mode off`() {
        val entries = settingsSearchIndex(developerMode = false)
        val routes = entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `no duplicate routes with developer mode on`() {
        val entries = settingsSearchIndex(developerMode = true)
        val routes = entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
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
    fun `every row belongs to one of the seven hub groups`() {
        val groups = setOf(
            R.string.setting_page_group_appearance,
            R.string.setting_page_group_models,
            R.string.setting_page_group_assistant,
            R.string.setting_page_group_device,
            R.string.setting_page_group_connections,
            R.string.setting_page_group_data,
            R.string.setting_page_group_about,
            // The developer-only row reuses the settings page title as its group.
            R.string.settings,
        )
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
    fun `the theme page answers to the colour-mode alias`() {
        val entry = settingsSearchIndex(developerMode = false)
            .single { it.route == Screen.SettingPreferencesTheme }
        assertTrue(entry.altTitles.contains(R.string.setting_page_color_mode))
    }
}
