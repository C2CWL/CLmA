package com.clma.app.data.datastore

import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reasoning picker style defaults to the energy bar. Missing keys must read back as
 * [ReasoningPickerStyle.ENERGY_BAR] so existing installs keep the Codex capsule.
 */
class DisplaySettingReasoningPickerStyleTest {

    @Test
    fun `reasoning picker style is energy bar by default`() {
        assertEquals(ReasoningPickerStyle.ENERGY_BAR, DisplaySetting().reasoningPickerStyle)
    }

    @Test
    fun `stored settings without the key read back as energy bar`() {
        val legacy = """{"showUserAvatar":false,"showTokenUsage":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertEquals(ReasoningPickerStyle.ENERGY_BAR, decoded.reasoningPickerStyle)
        assertEquals(false, decoded.showUserAvatar)
    }

    @Test
    fun `stellar round-trips`() {
        val json = JsonInstant.encodeToString(
            DisplaySetting(reasoningPickerStyle = ReasoningPickerStyle.STELLAR),
        )
        assertEquals(true, json.contains("\"reasoningPickerStyle\":\"stellar\""))
        assertEquals(
            ReasoningPickerStyle.STELLAR,
            JsonInstant.decodeFromString<DisplaySetting>(json).reasoningPickerStyle,
        )
    }

    @Test
    fun `energy bar round-trips`() {
        val json = JsonInstant.encodeToString(
            DisplaySetting(reasoningPickerStyle = ReasoningPickerStyle.ENERGY_BAR),
        )
        assertEquals(true, json.contains("\"reasoningPickerStyle\":\"energy_bar\""))
        assertEquals(
            ReasoningPickerStyle.ENERGY_BAR,
            JsonInstant.decodeFromString<DisplaySetting>(json).reasoningPickerStyle,
        )
    }
}
