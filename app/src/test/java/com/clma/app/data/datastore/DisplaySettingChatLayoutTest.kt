package com.clma.app.data.datastore

import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat layout is a three-way selector that supersedes the old cleanLayout switch. GEMINI is the
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
    fun `legacy original resolves to gemini`() {
        val decoded = JsonInstant.decodeFromString<DisplaySetting>("""{"chatLayout":"original"}""")
        assertEquals(ChatLayout.GEMINI, decoded.effectiveChatLayout)
        assertTrue(decoded.usesGeminiChatUi)
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
        assertEquals(ChatLayout.GEMINI, original.chatLayout)
        assertTrue(original.cleanLayout)

        val clean = DisplaySetting().withChatLayout(ChatLayout.CLEAN)
        assertEquals(ChatLayout.CLEAN, clean.chatLayout)
        assertTrue(clean.cleanLayout)

        // GEMINI maps cleanLayout=true so an older build falls back to the closer chrome.
        val gemini = DisplaySetting().withChatLayout(ChatLayout.GEMINI)
        assertEquals(ChatLayout.GEMINI, gemini.chatLayout)
        assertTrue(gemini.cleanLayout)
        assertEquals(ChatLayout.GEMINI, gemini.effectiveChatLayout)
        assertTrue(gemini.usesGeminiChatUi)

        // MODERN maps cleanLayout=true so an older build falls back to the closer chrome.
        val modern = DisplaySetting().withChatLayout(ChatLayout.MODERN)
        assertEquals(ChatLayout.MODERN, modern.chatLayout)
        assertTrue(modern.cleanLayout)
        assertEquals(ChatLayout.MODERN, modern.effectiveChatLayout)
    }

    @Test
    fun `explicit chatLayout round-trips and wins over the legacy switch`() {
        val json = JsonInstant.encodeToString(
            DisplaySetting(chatLayout = ChatLayout.MODERN, cleanLayout = false),
        )
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(json)
        assertEquals(ChatLayout.MODERN, decoded.chatLayout)
        // Explicit chatLayout wins even if the legacy flag disagrees.
        assertEquals(ChatLayout.MODERN, decoded.effectiveChatLayout)
    }

    @Test
    fun `picking a loading indicator style does not touch the chat layout`() {
        val setting = DisplaySetting(chatLayout = ChatLayout.MODERN)
            .withLoadingIndicatorStyle(LoadingIndicatorStyle.TWIN_ORBIT)
        assertEquals(LoadingIndicatorStyle.TWIN_ORBIT, setting.effectiveLoadingIndicatorStyle)
        assertEquals(ChatLayout.MODERN, setting.effectiveChatLayout)
    }

    @Test
    fun `modern chrome applies only to the modern layout`() {
        val modern = DisplaySetting(chatLayout = ChatLayout.MODERN)
        assertTrue(modern.usesModernChatUi)
        // 简洁 renders its own (old) look again, never the modern chrome.
        assertFalse(DisplaySetting(chatLayout = ChatLayout.CLEAN).usesModernChatUi)
        assertFalse(DisplaySetting(cleanLayout = true).usesModernChatUi)
        assertFalse(DisplaySetting().usesModernChatUi)
        assertFalse(DisplaySetting(chatLayout = ChatLayout.GEMINI).usesModernChatUi)
    }

    @Test
    fun `stored clean value still decodes to clean`() {
        val decoded = JsonInstant.decodeFromString<DisplaySetting>("""{"chatLayout":"clean"}""")
        assertEquals(ChatLayout.CLEAN, decoded.effectiveChatLayout)
        assertFalse(decoded.usesModernChatUi)
    }

    @Test
    fun `retired warm value claude decodes to modern`() {
        // 暖阳 was stored as "claude"; 现代 took that slot, so those installs land on 现代.
        val decoded = JsonInstant.decodeFromString<DisplaySetting>("""{"chatLayout":"claude"}""")
        assertEquals(ChatLayout.MODERN, decoded.chatLayout)
        assertEquals(ChatLayout.MODERN, decoded.effectiveChatLayout)
        assertTrue(decoded.usesModernChatUi)
        assertTrue(JsonInstant.encodeToString(DisplaySetting(chatLayout = ChatLayout.MODERN)).contains("\"claude\""))
    }
}
