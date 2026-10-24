package com.clma.app.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
import com.clma.app.data.datastore.capsuleSubject
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

/** The subject-first and subject-less copy variants of a capsule title. */
private data class CapsuleCopy(val withSubject: Int, val empty: Int)

private fun capsuleCopyOf(kind: CapsuleToolKind): CapsuleCopy = when (kind) {
    CapsuleToolKind.SEARCH -> CapsuleCopy(
        R.string.chat_message_tool_search_capsule,
        R.string.chat_message_tool_search_capsule_empty,
    )

    CapsuleToolKind.READ -> CapsuleCopy(
        R.string.chat_message_tool_read_page_capsule,
        R.string.chat_message_tool_read_page_capsule_empty,
    )

    CapsuleToolKind.FETCH -> CapsuleCopy(
        R.string.chat_message_tool_fetch_page_capsule,
        R.string.chat_message_tool_fetch_page_capsule_empty,
    )

    CapsuleToolKind.RUN -> CapsuleCopy(
        R.string.chat_message_tool_run_capsule,
        R.string.chat_message_tool_run_capsule_empty,
    )

    CapsuleToolKind.RUN_JS -> CapsuleCopy(
        R.string.chat_message_tool_run_js_capsule,
        R.string.chat_message_tool_run_js_capsule_empty,
    )

    CapsuleToolKind.SKILL -> CapsuleCopy(
        R.string.chat_message_tool_skill_capsule,
        R.string.chat_message_tool_skill_capsule_empty,
    )

    CapsuleToolKind.READ_FILE -> CapsuleCopy(
        R.string.chat_message_tool_read_file_capsule,
        R.string.chat_message_tool_read_file_capsule_empty,
    )

    CapsuleToolKind.WRITE_FILE -> CapsuleCopy(
        R.string.chat_message_tool_write_file_capsule,
        R.string.chat_message_tool_write_file_capsule_empty,
    )

    CapsuleToolKind.EDIT_FILE -> CapsuleCopy(
        R.string.chat_message_tool_edit_file_capsule,
        R.string.chat_message_tool_edit_file_capsule_empty,
    )

    // Memory is a fixed sentence per action, handled before this lookup.
    CapsuleToolKind.MEMORY -> CapsuleCopy(
        R.string.chat_message_tool_memory_capsule,
        R.string.chat_message_tool_memory_capsule,
    )
}

/** Read a top-level string argument; anything else (missing, object, array) is null. */
private fun JsonElement?.stringArg(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull

/**
 * Capsule title, or null so the caller keeps the classic string.
 *
 * Web tools prefer the host, search shows the query, shell the command, file tools
 * the path, skill tools the skill name, and memory says what happened to memory.
 */
@Composable
fun capsuleToolTitle(toolName: String, arguments: JsonElement?): String? {
    val kind = capsuleToolKind(toolName) ?: return null
    val subject = capsuleSubject(kind) { key -> arguments.stringArg(key) }

    if (kind == CapsuleToolKind.MEMORY) {
        return when (subject) {
            "create" -> stringResource(R.string.chat_message_tool_create_memory)
            "edit" -> stringResource(R.string.chat_message_tool_edit_memory)
            "delete" -> stringResource(R.string.chat_message_tool_delete_memory)
            else -> stringResource(R.string.chat_message_tool_memory_capsule)
        }
    }

    val copy = capsuleCopyOf(kind)
    val preferHost = kind == CapsuleToolKind.READ || kind == CapsuleToolKind.FETCH
    val label = capsuleArgumentLabel(subject, preferHost = preferHost)
    return if (label != null) {
        stringResource(copy.withSubject, label)
    } else {
        stringResource(copy.empty)
    }
}

fun ToolCallStyle.stepIcon(toolName: String, classic: ImageVector): ImageVector =
    if (this == ToolCallStyle.CAPSULE) capsuleToolIcon(toolName) else classic

@Composable
fun ToolCallStyle.stepTitle(
    toolName: String,
    classicTitle: String,
    arguments: JsonElement?,
): String = if (this == ToolCallStyle.CAPSULE) {
    capsuleToolTitle(toolName, arguments) ?: classicTitle
} else {
    classicTitle
}
