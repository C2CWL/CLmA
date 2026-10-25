package com.clma.app.data.datastore

import com.clma.app.utils.JsonInstant
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
    fun `capsule copy covers web, shell, file, skill and memory tools`() {
        assertEquals(CapsuleToolKind.SEARCH, capsuleToolKind("search_web"))
        assertEquals(CapsuleToolKind.SEARCH, capsuleToolKind("conversation_search"))
        assertEquals(CapsuleToolKind.SEARCH, capsuleToolKind("current_conversation_search"))
        assertEquals(CapsuleToolKind.READ_FILE, capsuleToolKind("tool_output_read"))
        assertEquals(CapsuleToolKind.READ, capsuleToolKind("scrape_web"))
        assertEquals(CapsuleToolKind.READ, capsuleToolKind("web_extract"))
        assertEquals(CapsuleToolKind.FETCH, capsuleToolKind("web_fetch"))
        assertEquals(CapsuleToolKind.RUN, capsuleToolKind("workspace_shell"))
        assertEquals(CapsuleToolKind.RUN_JS, capsuleToolKind("run_js"))
        assertEquals(CapsuleToolKind.SKILL, capsuleToolKind("use_skill"))
        assertEquals(CapsuleToolKind.READ_FILE, capsuleToolKind("workspace_read_file"))
        assertEquals(CapsuleToolKind.WRITE_FILE, capsuleToolKind("workspace_write_file"))
        assertEquals(CapsuleToolKind.EDIT_FILE, capsuleToolKind("workspace_edit_file"))
        assertEquals(CapsuleToolKind.MEMORY, capsuleToolKind("memory_tool"))
        // Tools without capsule copy keep the classic title and icon.
        assertNull(capsuleToolKind("get_time_info"))
        assertNull(capsuleToolKind(""))
    }

    @Test
    fun `capsule subject reads the argument each tool actually sends`() {
        val args = mapOf(
            "query" to "kotlin compose",
            "url" to "https://example.com/x",
            "command" to "ls -la",
            "path" to "/workspace/notes.md",
            "skill_name" to "qr-code",
            "name" to "agent-core",
            "action" to "edit",
        )
        val arg: (String) -> String? = { args[it] }

        assertEquals("kotlin compose", capsuleSubject(CapsuleToolKind.SEARCH, arg))
        assertEquals("https://example.com/x", capsuleSubject(CapsuleToolKind.READ, arg))
        assertEquals("https://example.com/x", capsuleSubject(CapsuleToolKind.FETCH, arg))
        assertEquals("ls -la", capsuleSubject(CapsuleToolKind.RUN, arg))
        assertEquals("qr-code", capsuleSubject(CapsuleToolKind.RUN_JS, arg))
        assertEquals("agent-core", capsuleSubject(CapsuleToolKind.SKILL, arg))
        assertEquals("/workspace/notes.md", capsuleSubject(CapsuleToolKind.READ_FILE, arg))
        assertEquals("call_out.txt", capsuleSubject(CapsuleToolKind.READ_FILE) { if (it == "file") "call_out.txt" else null })
        assertEquals("/workspace/notes.md", capsuleSubject(CapsuleToolKind.WRITE_FILE, arg))
        assertEquals("/workspace/notes.md", capsuleSubject(CapsuleToolKind.EDIT_FILE, arg))
        assertEquals("edit", capsuleSubject(CapsuleToolKind.MEMORY, arg))
        // A missing argument is null, so the caller falls back to the empty copy.
        assertNull(capsuleSubject(CapsuleToolKind.SEARCH) { null })
    }

    @Test
    fun `capsule label prefers a host and truncates`() {
        assertEquals("example.com", capsuleArgumentLabel("https://www.example.com/a/b", preferHost = true))
        assertEquals("not a url", capsuleArgumentLabel("not a url", preferHost = true))
        assertNull(capsuleArgumentLabel("  ", preferHost = false))
        // Exactly at the limit still fits, so it is left alone.
        assertEquals("1234567890", capsuleArgumentLabel("1234567890", preferHost = false, max = 10))
        assertEquals("123456789…", capsuleArgumentLabel("12345678901", preferHost = false, max = 10))
    }

    @Test
    fun `capsule label collapses whitespace so a command stays single line`() {
        assertEquals("ls -la", capsuleArgumentLabel("ls\n  -la", preferHost = false))
    }
}
