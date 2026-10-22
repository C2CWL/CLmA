package com.clma.app.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.clma.app.R
import com.clma.app.data.datastore.CapsuleToolKind
import com.clma.app.data.datastore.ToolCallStyle
import com.clma.app.data.datastore.capsuleArgumentLabel
import com.clma.app.data.datastore.capsuleToolKind

/** Capsule step glyph size (classic timeline stays 16.dp). */
val CapsuleToolIconSize = 22.dp

/**
 * Full capsule icon pack. Every known tool gets a modern glyph; unknown tools
 * fall back to [HugeIcons.Rocket01] so the chip never reuses the old wrench look.
 */
fun capsuleToolIcon(toolName: String): ImageVector = when (toolName) {
    // S1 Search — GlobalSearch (earth + loupe)
    "search_web",
    "conversation_search",
    -> HugeIcons.GlobalSearch

    // S2 Web
    "scrape_web",
    "web_extract",
    "web_fetch",
    -> HugeIcons.Internet

    // S3 Files
    "workspace_read_file" -> HugeIcons.FileView
    "workspace_edit_file" -> HugeIcons.FileEdit
    "workspace_write_file" -> HugeIcons.FileAdd

    // S4 Shell
    "workspace_shell" -> HugeIcons.ComputerTerminal01

    // S5 Skill / magic
    "use_skill",
    "run_js",
    "context_compaction",
    -> HugeIcons.AiMagic

    // S6 Memory
    "memory_tool" -> HugeIcons.Eraser

    // S7 Time / calendar
    "get_time_info" -> HugeIcons.Time02
    "calendar_query" -> HugeIcons.Calendar03
    "calendar_create",
    "create_calendar_event",
    -> HugeIcons.CalendarAdd01

    // S8 Device / comms
    "get_screen_time",
    "create_contact",
    "send_sms_intent",
    -> HugeIcons.SmartPhone01

    "recent_chats",
    "send_email_intent",
    -> HugeIcons.Message02

    "clipboard_tool" -> HugeIcons.Clipboard
    "text_to_speech" -> HugeIcons.VolumeHigh

    // S9 System / place
    "open_wifi_settings" -> HugeIcons.Settings03
    "show_location_on_map" -> HugeIcons.Pin

    // S10 Fallback (server tools, MCP, unregistered, …)
    else -> HugeIcons.Rocket01
}

/**
 * Capsule title, or null so the caller keeps the classic string.
 * Search uses the query; page tools use the host when the argument is a URL.
 */
@Composable
fun capsuleToolTitle(toolName: String, query: String?, url: String?): String? {
    val kind = capsuleToolKind(toolName) ?: return null
    return when (kind) {
        CapsuleToolKind.SEARCH -> {
            val label = capsuleArgumentLabel(query, preferHost = false)
            if (label == null) {
                stringResource(R.string.chat_message_tool_search_capsule_empty)
            } else {
                stringResource(R.string.chat_message_tool_search_capsule, label)
            }
        }

        CapsuleToolKind.READ -> {
            val label = capsuleArgumentLabel(url, preferHost = true)
            if (label == null) {
                stringResource(R.string.chat_message_tool_read_page_capsule_empty)
            } else {
                stringResource(R.string.chat_message_tool_read_page_capsule, label)
            }
        }

        CapsuleToolKind.FETCH -> {
            val label = capsuleArgumentLabel(url, preferHost = true)
            if (label == null) {
                stringResource(R.string.chat_message_tool_fetch_page_capsule_empty)
            } else {
                stringResource(R.string.chat_message_tool_fetch_page_capsule, label)
            }
        }
    }
}

fun ToolCallStyle.stepIcon(toolName: String, classic: ImageVector): ImageVector =
    if (this == ToolCallStyle.CAPSULE) capsuleToolIcon(toolName) else classic

@Composable
fun ToolCallStyle.stepTitle(
    toolName: String,
    classicTitle: String,
    query: String?,
    url: String?,
): String = if (this == ToolCallStyle.CAPSULE) {
    capsuleToolTitle(toolName, query, url) ?: classicTitle
} else {
    classicTitle
}
