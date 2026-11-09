package com.clma.app.ui.pages.chat.botmode

import kotlin.uuid.Uuid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.botmode.isBotPinned
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.model.Assistant
import com.clma.app.ui.components.ui.AssistantAvatar

/**
 * Contact-style multi-bot list for Bot mode. Maps existing [Assistant] rows; pin/hide menu
 * is a skeleton that writes DisplaySetting lists (no parallel bot model).
 */
@Composable
fun BotContactList(
    bots: List<Assistant>,
    currentId: Uuid,
    displaySetting: DisplaySetting,
    modelNameOf: (Assistant) -> String?,
    unreadOf: (Assistant) -> Int = { 0 },
    onSelect: (Assistant) -> Unit,
    onTogglePin: (Assistant) -> Unit,
    onToggleHide: (Assistant) -> Unit,
    onOpenDetail: (Assistant) -> Unit,
    onOpenTasks: (Assistant) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.bot_mode_contact_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            bots.forEach { bot ->
                BotContactRow(
                    bot = bot,
                    selected = bot.id == currentId,
                    pinned = displaySetting.isBotPinned(bot.id),
                    modelName = modelNameOf(bot),
                    unread = unreadOf(bot),
                    onClick = { onSelect(bot) },
                    onTogglePin = { onTogglePin(bot) },
                    onToggleHide = { onToggleHide(bot) },
                    onOpenDetail = { onOpenDetail(bot) },
                    onOpenTasks = { onOpenTasks(bot) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotContactRow(
    bot: Assistant,
    selected: Boolean,
    pinned: Boolean,
    modelName: String?,
    unread: Int,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleHide: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenTasks: () -> Unit,
) {
    var menuOpen by remember(bot.id) { mutableStateOf(false) }
    val name = bot.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            ),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
        },
        tonalElevation = if (selected) 1.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box {
                AssistantAvatar(
                    assistant = bot,
                    name = name,
                    onUpdate = {},
                    modifier = Modifier.size(44.dp),
                )
                if (unread > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (unread > 9) "9+" else unread.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (pinned) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.bot_mode_pinned_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = modelName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.bot_mode_model_inherit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (pinned) R.string.bot_mode_menu_unpin else R.string.bot_mode_menu_pin
                            )
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onTogglePin()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bot_mode_menu_hide)) },
                    onClick = {
                        menuOpen = false
                        onToggleHide()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bot_mode_menu_detail)) },
                    onClick = {
                        menuOpen = false
                        onOpenDetail()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bot_mode_menu_tasks)) },
                    onClick = {
                        menuOpen = false
                        onOpenTasks()
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}
