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
    fun `every category has its own destination and description`() {
        val routes = settingsHubSections.map { it.route }
        assertEquals(settingsHubSections.size, routes.distinct().size)
        settingsHubSections.forEach { section ->
            assertEquals(section.id, section.route.sectionId)
            assertTrue(section.descriptionRes != 0)
        }
    }

    @Test
    fun `connectors are navigable and searchable exactly once`() {
        val connector = settingsHubItems.getValue(SettingsHubItemId.CONNECTORS)
        assertEquals(Screen.SettingConnectors, connector.route)
        assertEquals(R.string.setting_connectors_title, connector.titleRes)
        assertEquals(R.string.setting_connectors_desc, connector.descriptionRes)
        assertTrue(settingsHubSections.single { it.id == SettingsHubSectionId.CONNECTIONS }.items.contains(connector.id))
        assertEquals(1, settingsHubSearchRows().count { it.first.id == connector.id })
    }
}
