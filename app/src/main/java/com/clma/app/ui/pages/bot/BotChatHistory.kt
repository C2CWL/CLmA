// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/PinnedMessagesMenu.tsx, src/components/message-menu.tsx, server/routes/threads.ts, src/components/UnreadContextOverlay.tsx, src/components/UnreadContextCard.tsx, src/components/AttachmentGallery.tsx, server/conversation-attachments.ts
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE).
// Modified: ported to Kotlin and changed for CLmA.

package com.clma.app.ui.pages.bot

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.R
import com.clma.app.data.botmode.botChatText
import com.clma.app.data.model.MessageNode
import com.clma.app.utils.FileOpenUtil
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide

private data class HistoryAttachment(val url: String, val name: String?, val mime: String? = null)

private fun List<UIMessagePart>.historyAttachments(): Sequence<HistoryAttachment> = asSequence().flatMap { part ->
    when (part) {
        is UIMessagePart.Document -> sequenceOf(HistoryAttachment(part.url, part.fileName, part.mime))
        is UIMessagePart.Image -> sequenceOf(HistoryAttachment(part.url, null, "image/*"))
        is UIMessagePart.Audio -> sequenceOf(HistoryAttachment(part.url, null, "audio/*"))
        is UIMessagePart.Video -> sequenceOf(HistoryAttachment(part.url, null, "video/*"))
        is UIMessagePart.Tool -> part.output.historyAttachments()
        else -> emptySequence()
    }
}.filter { it.url.toUri().scheme in setOf("file", "content", "http", "https") }

@Composable
private fun messagePreview(message: UIMessage): String =
    message.botChatText().take(240).ifBlank {
        if (message.parts.any { it is UIMessagePart.Image } &&
            message.parts.all { it is UIMessagePart.Text || it is UIMessagePart.Image }
        ) {
            stringResource(R.string.bot_unread_images)
        } else {
            stringResource(R.string.bot_gallery_title)
        }
    }

@Composable
internal fun BotHistoryMenu(
    nodes: List<MessageNode>,
    pinnedMessage: UIMessage?,
    onJumpToPin: () -> Unit,
    onUnpin: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(Lucide.EllipsisVertical, stringResource(R.string.accessibility_chat_options))
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bot_pinned_title)) },
                onClick = { showMenu = false; showPin = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bot_gallery_title)) },
                onClick = { showMenu = false; showGallery = true },
            )
        }
    }
    if (showPin) {
        AlertDialog(
            onDismissRequest = { showPin = false },
            title = { Text(stringResource(R.string.bot_pinned_title)) },
            text = {
                Text(
                    pinnedMessage?.let { messagePreview(it) } ?: stringResource(R.string.bot_pinned_empty),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                if (pinnedMessage != null) {
                    TextButton(onClick = { showPin = false; onJumpToPin() }) {
                        Text(stringResource(R.string.bot_pinned_jump))
                    }
                } else {
                    TextButton(onClick = { showPin = false }) { Text(stringResource(R.string.bot_history_close)) }
                }
            },
            dismissButton = {
                if (pinnedMessage != null) {
                    TextButton(onClick = { showPin = false; onUnpin() }) {
                        Text(stringResource(R.string.bot_pinned_remove))
                    }
                }
            },
        )
    }
    if (showGallery) BotAttachmentGallery(nodes) { showGallery = false }
}

@Composable
private fun BotAttachmentGallery(nodes: List<MessageNode>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val files = remember(nodes) {
        nodes.asReversed().asSequence().flatMap { node ->
            node.currentMessage.parts.historyAttachments()
        }.distinctBy { it.url }.toList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bot_gallery_title)) },
        text = {
            if (files.isEmpty()) {
                Text(stringResource(R.string.bot_gallery_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(files, key = { it.url }) { file ->
                        val uri = file.url.toUri()
                        val name = file.name?.takeIf { it.isNotBlank() }
                            ?: uri.lastPathSegment ?: stringResource(R.string.bot_gallery_title)
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                FileOpenUtil.openUri(context, uri, file.mime).onFailure {
                                    Toast.makeText(context, R.string.attachment_open_failed, Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) {
                            Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bot_history_close)) } },
    )
}

@Composable
internal fun BotUnreadContextCard(message: UIMessage, onJump: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp)) {
            Text(stringResource(R.string.bot_unread_previous), style = MaterialTheme.typography.labelMedium)
            Text(messagePreview(message), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row {
                TextButton(onClick = onJump) { Text(stringResource(R.string.bot_unread_jump)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.bot_history_close)) }
            }
        }
    }
}
