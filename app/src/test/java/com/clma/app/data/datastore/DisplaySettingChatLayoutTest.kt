package com.clma.app.data.datastore

import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat layout is a four-way selector that supersedes the old cleanLayout switch. GEMINI is the
 * default look; an install that only knew the boolean keeps whatever it said (clean stays clean,
 * the old original chrome is gone and resolves to the new default).
 */
class DisplaySettingChatLayoutTest {

    @Test
    fun `chat layout is unset by default and resolves to gemini`() {
        val setting = DisplaySetting()
        assertNull(setting.chatLayout)
        assertEquals(ChatLayout.GEMINI, setting.effectiveChatLayout)
        assertTrue(setting.usesGeminiChatUi)
        assertFalse(setting.cleanLayout)
    }

    @Test
    fun `legacy cleanLayout true resolves to clean when chatLayout is unset`() {
        val legacy = """{"cleanLayout":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertNull(decoded.chatLayout)
        assertTrue(decoded.cleanLayout)
        assertEquals(ChatLayout.CLEAN, decoded.effectiveChatLayout)
    }

    @Test
    fun `legacy cleanLayout false resolves to gemini when chatLayout is unset`() {
        val legacy = """{"cleanLayout":false,"showUserAvatar":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertFalse(decoded.cleanLayout)
        assertEquals(ChatLayout.GEMINI, decoded.effectiveChatLayout)
    }

    @Test
    fun `picking original stays on original`() {
        val decoded = JsonInstant.decodeFromString<DisplaySetting>("""{"chatLayout":"original"}""")
        assertEquals(ChatLayout.ORIGINAL, decoded.effectiveChatLayout)
        assertFalse(decoded.usesGeminiChatUi)
    }

    @Test
    fun `gemini serializes under its own name`() {
        val json = JsonInstant.encodeToString(DisplaySetting(chatLayout = ChatLayout.GEMINI))
        assertTrue(json.contains("\"chatLayout\":\"gemini\""))
        assertEquals(
            ChatLayout.GEMINI,
            JsonInstant.decodeFromString<DisplaySetting>(json).effectiveChatLayout,
        )
    }

    @Test
    fun `withChatLayout keeps the legacy switch in step`() {
        val original = DisplaySetting().withChatLayout(ChatLayout.ORIGINAL)
        assertEquals(ChatLayout.ORIGINAL, original.chatLayout)
        assertFalse(original.cleanLayout)

        val clean = DisplaySetting().withChatLayout(ChatLayout.CLEAN)
        assertEquals(ChatLayout.CLEAN, clean.chatLayout)
        assertTrue(clean.cleanLayout)

        // GEMINI maps cleanLayout=true so an older build falls back to the closer chrome.
        val gemini = DisplaySetting().withChatLayout(ChatLayout.GEMINI)
        assertEquals(ChatLayout.GEMINI, gemini.chatLayout)
        assertTrue(gemini.cleanLayout)
        assertEquals(ChatLayout.GEMINI, gemini.effectiveChatLayout)
        assertTrue(gemini.usesGeminiChatUi)

        // CLAUDE maps cleanLayout=true so an older build falls back to the closer chrome.
        val claude = DisplaySetting().withChatLayout(ChatLayout.CLAUDE)
        assertEquals(ChatLayout.CLAUDE, claude.chatLayout)
        assertTrue(claude.cleanLayout)
        assertEquals(ChatLayout.CLAUDE, claude.effectiveChatLayout)
    }

    @Test
    fun `explicit chatLayout round-trips and wins over the legacy switch`() {
        val json = JsonInstant.encodeToString(
            DisplaySetting(chatLayout = ChatLayout.CLAUDE, cleanLayout = false),
        )
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(json)
        assertEquals(ChatLayout.CLAUDE, decoded.chatLayout)
        // Explicit chatLayout wins even if the legacy flag disagrees.
        assertEquals(ChatLayout.CLAUDE, decoded.effectiveChatLayout)
    }

    @Test
    fun `picking a loading indicator style does not touch the chat layout`() {
        val setting = DisplaySetting(chatLayout = ChatLayout.CLAUDE)
            .withLoadingIndicatorStyle(LoadingIndicatorStyle.TWIN_ORBIT)
        assertEquals(LoadingIndicatorStyle.TWIN_ORBIT, setting.effectiveLoadingIndicatorStyle)
        assertEquals(ChatLayout.CLAUDE, setting.effectiveChatLayout)
    }
}
