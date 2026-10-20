package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tool-call style is opt-in. Missing keys must read back as classic so existing
 * installs keep the timeline.
 */
class DisplaySettingToolCallStyleTest {

    @Test
    fun `tool call style is classic by default`() {
        assertEquals(ToolCallStyle.CLASSIC, DisplaySetting().toolCallStyle)
    }

    @Test
    fun `stored settings without the key read back as classic`() {
        val legacy = """{"showUserAvatar":false,"showTokenUsage":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertEquals(ToolCallStyle.CLASSIC, decoded.toolCallStyle)
        assertEquals(false, decoded.showUserAvatar)
    }

    @Test
    fun `capsule round-trips`() {
        val json = JsonInstant.encodeToString(DisplaySetting(toolCallStyle = ToolCallStyle.CAPSULE))
        assertEquals(true, json.contains("\"toolCallStyle\":\"capsule\""))
        assertEquals(
            ToolCallStyle.CAPSULE,
            JsonInstant.decodeFromString<DisplaySetting>(json).toolCallStyle,
        )
    }

    @Test
    fun `capsule copy is limited to search and page tools`() {
        assertEquals(CapsuleToolKind.SEARCH, capsuleToolKind("search_web"))
        assertEquals(CapsuleToolKind.READ, capsuleToolKind("scrape_web"))
        assertEquals(CapsuleToolKind.READ, capsuleToolKind("web_extract"))
        assertEquals(CapsuleToolKind.FETCH, capsuleToolKind("web_fetch"))
        assertNull(capsuleToolKind("memory_tool"))
    }

    @Test
    fun `capsule label prefers a host and truncates`() {
        assertEquals("example.com", capsuleArgumentLabel("https://www.example.com/a/b", preferHost = true))
        assertEquals("not a url", capsuleArgumentLabel("not a url", preferHost = true))
        assertNull(capsuleArgumentLabel("  ", preferHost = false))
        assertEquals("123456789…", capsuleArgumentLabel("1234567890", preferHost = false, max = 10))
    }
}
