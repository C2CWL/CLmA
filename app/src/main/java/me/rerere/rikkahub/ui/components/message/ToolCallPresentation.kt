package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Browser
import me.rerere.hugeicons.stroke.DiscoverCircle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.CapsuleToolKind
import me.rerere.rikkahub.data.datastore.ToolCallStyle
import me.rerere.rikkahub.data.datastore.capsuleArgumentLabel
import me.rerere.rikkahub.data.datastore.capsuleToolKind

/** Capsule glyph for a tool, or null when classic (and every other tool) should keep its own icon. */
fun capsuleToolIcon(toolName: String): ImageVector? = when (capsuleToolKind(toolName)) {
    CapsuleToolKind.SEARCH -> HugeIcons.DiscoverCircle
    CapsuleToolKind.READ, CapsuleToolKind.FETCH -> HugeIcons.Browser
    null -> null
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
    if (this == ToolCallStyle.CAPSULE) capsuleToolIcon(toolName) ?: classic else classic

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
