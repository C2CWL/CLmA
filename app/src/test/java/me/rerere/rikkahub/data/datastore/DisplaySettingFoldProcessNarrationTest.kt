package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * foldProcessNarration defaults on so new installs get the cleaner process card.
 * Missing keys on old installs also read back as true (data-class default).
 */
class DisplaySettingFoldProcessNarrationTest {

    @Test
    fun `fold process narration is on by default`() {
        assertTrue(DisplaySetting().foldProcessNarration)
    }

    @Test
    fun `stored settings without the key read back as on`() {
        val legacy = """{"showUserAvatar":false,"showTokenUsage":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertTrue(decoded.foldProcessNarration)
        assertFalse(decoded.showUserAvatar)
    }

    @Test
    fun `off round-trips`() {
        val json = JsonInstant.encodeToString(DisplaySetting(foldProcessNarration = false))
        assertTrue(json.contains("\"foldProcessNarration\":false"))
        assertFalse(JsonInstant.decodeFromString<DisplaySetting>(json).foldProcessNarration)
    }
}
