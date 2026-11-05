package com.clma.app.ui.pages.setting

import com.clma.app.R
import com.clma.app.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHubCatalogTest {
    @Test
    fun `every setting appears exactly once in the hub`() {
        val ids = settingsHubSections.flatMap { it.items }
        assertEquals(SettingsHubItemId.entries.toSet(), ids.toSet())
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(ids.toSet(), settingsHubItems.keys)
    }

    @Test
    fun `hub has exactly six sections`() {
        assertEquals(6, settingsHubSections.size)
        assertEquals(
            setOf(
                SettingsHubSectionId.MODELS,
                SettingsHubSectionId.CHAT,
                SettingsHubSectionId.AGENT,
                SettingsHubSectionId.DEVICE,
                SettingsHubSectionId.APPEARANCE,
                SettingsHubSectionId.DATA,
            ),
            settingsHubSections.map { it.id }.toSet(),
        )
    }

    @Test
    fun `every category has its own destination and description`() {
        val routes = settingsHubSections.map { it.route }
        assertEquals(settingsHubSections.size, routes.distinct().size)
        settingsHubSections.forEach { section ->
            assertEquals(section.id, section.route.sectionId)
            assertTrue(section.descriptionRes != 0)
        }
    }

    @Test
    fun `connectors live under agent with workflows and schedules`() {
        val connector = settingsHubItems.getValue(SettingsHubItemId.CONNECTORS)
        assertEquals(Screen.SettingConnectors, connector.route)
        assertEquals(R.string.setting_connectors_title, connector.titleRes)
        assertEquals(R.string.setting_connectors_desc, connector.descriptionRes)
        val agent = settingsHubSections.single { it.id == SettingsHubSectionId.AGENT }
        assertTrue(agent.items.contains(SettingsHubItemId.CONNECTORS))
        assertTrue(agent.items.contains(SettingsHubItemId.WORKFLOWS))
        assertTrue(agent.items.contains(SettingsHubItemId.SCHEDULED))
        assertEquals(1, settingsHubSearchRows().count { it.first.id == connector.id })
    }

    @Test
    fun `web server and notification access live under environment`() {
        val device = settingsHubSections.single { it.id == SettingsHubSectionId.DEVICE }
        assertTrue(device.items.contains(SettingsHubItemId.WEB))
        assertTrue(device.items.contains(SettingsHubItemId.NOTIF_ACCESS))
        assertTrue(device.items.contains(SettingsHubItemId.TERMUX))
    }

    @Test
    fun `edge bar and message notifications stay under appearance`() {
        val appearance = settingsHubSections.single { it.id == SettingsHubSectionId.APPEARANCE }
        assertTrue(appearance.items.contains(SettingsHubItemId.FLOATING_BALL))
        assertTrue(appearance.items.contains(SettingsHubItemId.NOTIF_PREFS))
    }

    @Test
    fun `data section exposes data and about subsection labels`() {
        val data = settingsHubSections.single { it.id == SettingsHubSectionId.DATA }
        assertEquals(
            listOf(R.string.setting_hub_subsection_data, R.string.setting_hub_subsection_about),
            data.subsections.map { it.titleRes },
        )
        val groups = data.itemGroups()
        assertEquals(2, groups.size)
        assertEquals(R.string.setting_hub_subsection_data, groups[0].first)
        assertEquals(
            listOf(SettingsHubItemId.BACKUP, SettingsHubItemId.FILES, SettingsHubItemId.SECRETS),
            groups[0].second,
        )
        assertEquals(R.string.setting_hub_subsection_about, groups[1].first)
        assertEquals(
            listOf(
                SettingsHubItemId.DOCTOR,
                SettingsHubItemId.LOGS,
                SettingsHubItemId.ABOUT,
                SettingsHubItemId.SHARE,
            ),
            groups[1].second,
        )
    }
}
