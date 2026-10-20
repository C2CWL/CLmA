package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clean layout is an opt-in display setting: off by default, and settings stored before
 * the field existed must read back as off (so the chat page looks exactly as before).
 */
class DisplaySettingCleanLayoutTest {

    @Test
    fun `clean layout is off by default`() {
        assertFalse(DisplaySetting().cleanLayout)
    }

    @Test
    fun `stored settings without the key read back as off`() {
        val legacy = """{"showUserAvatar":false,"showTokenUsage":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertFalse(decoded.cleanLayout)
        assertFalse(decoded.showUserAvatar)
    }

    @Test
    fun `turning it on round-trips`() {
        val json = JsonInstant.encodeToString(DisplaySetting(cleanLayout = true))
        assertTrue(json.contains("\"cleanLayout\":true"))
        assertTrue(JsonInstant.decodeFromString<DisplaySetting>(json).cleanLayout)
    }

    @Test
    fun `clean layout glass defaults to true`() {
        assertTrue(DisplaySetting().cleanLayoutGlass)
    }

    @Test
    fun `stored settings without cleanLayoutGlass read back as true`() {
        val legacy = """{"cleanLayout":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertTrue(decoded.cleanLayout)
        assertTrue(decoded.cleanLayoutGlass)
    }

    @Test
    fun `toggling clean layout glass round-trips`() {
        val json = JsonInstant.encodeToString(DisplaySetting(cleanLayout = true, cleanLayoutGlass = false))
        assertTrue(json.contains("\"cleanLayoutGlass\":false"))
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(json)
        assertTrue(decoded.cleanLayout)
        assertFalse(decoded.cleanLayoutGlass)
    }

    @Test
    fun `panel opacity defaults to the full material strength`() {
        val setting = DisplaySetting()
        assertEquals(1f, setting.cleanLayoutTopOpacity, 0f)
        assertEquals(1f, setting.cleanLayoutBottomOpacity, 0f)
    }

    @Test
    fun `stored settings without panel opacity read back as full strength`() {
        val legacy = """{"cleanLayout":true,"cleanLayoutGlass":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertEquals(1f, decoded.cleanLayoutTopOpacity, 0f)
        assertEquals(1f, decoded.cleanLayoutBottomOpacity, 0f)
    }

    @Test
    fun `panel size defaults and reads back from older settings`() {
        val setting = DisplaySetting()
        assertEquals(48f, setting.cleanLayoutTopHeight, 0f)
        assertEquals(6f, setting.cleanLayoutBottomPadding, 0f)
        val legacy = """{"cleanLayout":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertEquals(48f, decoded.cleanLayoutTopHeight, 0f)
        assertEquals(6f, decoded.cleanLayoutBottomPadding, 0f)
    }

    @Test
    fun `panel size round-trips`() {
        val json = JsonInstant.encodeToString(
            DisplaySetting(cleanLayout = true, cleanLayoutTopHeight = 56f, cleanLayoutBottomPadding = 4f)
        )
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(json)
        assertEquals(56f, decoded.cleanLayoutTopHeight, 0f)
        assertEquals(4f, decoded.cleanLayoutBottomPadding, 0f)
    }

    @Test
    fun `panel opacity round-trips`() {
        val json = JsonInstant.encodeToString(
            DisplaySetting(cleanLayout = true, cleanLayoutTopOpacity = 0.4f, cleanLayoutBottomOpacity = 0f)
        )
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(json)
        assertEquals(0.4f, decoded.cleanLayoutTopOpacity, 0f)
        assertEquals(0f, decoded.cleanLayoutBottomOpacity, 0f)
    }
}
