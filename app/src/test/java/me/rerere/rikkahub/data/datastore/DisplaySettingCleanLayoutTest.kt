package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
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
}
