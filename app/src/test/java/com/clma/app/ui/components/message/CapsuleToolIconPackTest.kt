package com.clma.app.ui.components.message

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.CalendarAdd01
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.VolumeHigh
import com.clma.app.data.datastore.ToolCallStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Capsule icon pack selections (user-confirmed 2026-10-20).
 * Classic timeline keeps its own per-tool icons via [ToolCallStyle.stepIcon].
 */
class CapsuleToolIconPackTest {

    @Test
    fun `search uses GlobalSearch`() {
        assertEquals(HugeIcons.GlobalSearch, capsuleToolIcon("search_web"))
        assertEquals(HugeIcons.GlobalSearch, capsuleToolIcon("conversation_search"))
    }

    @Test
    fun `web tools use Internet`() {
        assertEquals(HugeIcons.Internet, capsuleToolIcon("scrape_web"))
        assertEquals(HugeIcons.Internet, capsuleToolIcon("web_fetch"))
        assertEquals(HugeIcons.Internet, capsuleToolIcon("web_extract"))
    }

    @Test
    fun `files shell skill memory time device system`() {
        assertEquals(HugeIcons.FileView, capsuleToolIcon("workspace_read_file"))
        assertEquals(HugeIcons.FileEdit, capsuleToolIcon("workspace_edit_file"))
        assertEquals(HugeIcons.FileAdd, capsuleToolIcon("workspace_write_file"))
        assertEquals(HugeIcons.ComputerTerminal01, capsuleToolIcon("workspace_shell"))
        assertEquals(HugeIcons.AiMagic, capsuleToolIcon("use_skill"))
        assertEquals(HugeIcons.AiMagic, capsuleToolIcon("run_js"))
        assertEquals(HugeIcons.AiMagic, capsuleToolIcon("context_compaction"))
        assertEquals(HugeIcons.Eraser, capsuleToolIcon("memory_tool"))
        assertEquals(HugeIcons.Time02, capsuleToolIcon("get_time_info"))
        assertEquals(HugeIcons.Calendar03, capsuleToolIcon("calendar_query"))
        assertEquals(HugeIcons.CalendarAdd01, capsuleToolIcon("calendar_create"))
        assertEquals(HugeIcons.CalendarAdd01, capsuleToolIcon("create_calendar_event"))
        assertEquals(HugeIcons.SmartPhone01, capsuleToolIcon("get_screen_time"))
        assertEquals(HugeIcons.Message02, capsuleToolIcon("recent_chats"))
        assertEquals(HugeIcons.Clipboard, capsuleToolIcon("clipboard_tool"))
        assertEquals(HugeIcons.VolumeHigh, capsuleToolIcon("text_to_speech"))
        assertEquals(HugeIcons.Settings03, capsuleToolIcon("open_wifi_settings"))
        assertEquals(HugeIcons.Pin, capsuleToolIcon("show_location_on_map"))
    }

    @Test
    fun `unknown tools fall back to Rocket01`() {
        assertEquals(HugeIcons.Rocket01, capsuleToolIcon("some_mcp_tool"))
        assertEquals(HugeIcons.Rocket01, capsuleToolIcon(""))
    }

    @Test
    fun `stepIcon only swaps in capsule mode`() {
        val classic = HugeIcons.Rocket01
        // Use a distinct classic stand-in: Internet is not the search pack icon.
        val searchClassic = HugeIcons.Internet
        assertEquals(searchClassic, ToolCallStyle.CLASSIC.stepIcon("search_web", searchClassic))
        assertEquals(HugeIcons.GlobalSearch, ToolCallStyle.CAPSULE.stepIcon("search_web", searchClassic))
        assertNotEquals(classic, ToolCallStyle.CAPSULE.stepIcon("search_web", classic))
    }
}
