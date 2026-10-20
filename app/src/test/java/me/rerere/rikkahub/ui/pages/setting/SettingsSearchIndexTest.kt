package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
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
            .single { it.route == Screen.SettingPreferencesTheme && it.parentTitleRes == null }
        assertTrue(entry.altTitles.contains(R.string.setting_page_color_mode))
    }

    @Test
    fun `nested ui rows point at the ui preferences page`() {
        val nested = settingsSearchIndex(developerMode = false)
            .filter { it.route == Screen.SettingPreferencesUI && it.parentTitleRes != null }
        assertTrue(nested.size > 10)
        assertTrue(nested.any { it.titleRes == R.string.setting_display_page_popup_menu_style_title })
        nested.forEach { entry ->
            assertEquals(R.string.setting_page_preferences_ui, entry.parentTitleRes)
        }
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
    fun `floating ball nested alpha is indexed`() {
        val entry = settingsSearchIndex(developerMode = false).single {
            it.route == Screen.SettingFloatingBall &&
                it.titleRes == R.string.setting_floating_ball_alpha_title
        }
        assertNotNull(entry.parentTitleRes)
        assertEquals(R.string.setting_floating_ball_page_title, entry.parentTitleRes)
    }
}
