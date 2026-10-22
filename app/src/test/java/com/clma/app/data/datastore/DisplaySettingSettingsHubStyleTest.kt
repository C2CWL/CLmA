package com.clma.app.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaySettingSettingsHubStyleTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `default hub style is reorganized`() {
        assertEquals(SettingsHubStyle.REORGANIZED, DisplaySetting().settingsHubStyle)
    }

    @Test
    fun `missing hub style field falls back to reorganized`() {
        val decoded = json.decodeFromString(DisplaySetting.serializer(), """{"showUserAvatar":true}""")
        assertEquals(SettingsHubStyle.REORGANIZED, decoded.settingsHubStyle)
    }

    @Test
    fun `classic hub style round-trips`() {
        val original = DisplaySetting(settingsHubStyle = SettingsHubStyle.CLASSIC)
        val encoded = json.encodeToString(DisplaySetting.serializer(), original)
        val decoded = json.decodeFromString(DisplaySetting.serializer(), encoded)
        assertEquals(SettingsHubStyle.CLASSIC, decoded.settingsHubStyle)
    }
}
