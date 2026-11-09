package com.clma.app.ui.pages.bot

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.botmode.BotTimeLabelKind
import com.clma.app.data.botmode.botTimeLabel
import com.clma.app.ui.components.ui.AssistantAvatar
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Search
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

/**
 * Chat-app style bot list: avatar, name, one short preview line, time, and what needs the
 * user (unread count, waiting-for-approval tag, running spinner). Long-press for actions.
 */
@Composable
internal fun BotListTab(
    items: List<BotHomeItem>,
    hiddenCount: Int,
    onOpen: (BotHomeItem) -> Unit,
    onTogglePin: (BotHomeItem) -> Unit,
    onToggleUnread: (BotHomeItem) -> Unit,
    onHide: (BotHomeItem) -> Unit,
    onDetail: (BotHomeItem) -> Unit,
    onSetMain: (BotHomeItem) -> Unit,
    onDelete: (BotHomeItem) -> Unit,
    onManageHidden: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val defaultName = stringResource(R.string.assistant_page_default_assistant)
    val shown = remember(items, query, defaultName) {
        val q = query.trim()
        if (q.isEmpty()) items else items.filter { it.bot.name.ifBlank { defaultName }.contains(q, ignoreCase = true) }
    }
    val now = remember(items) { Instant.now() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.bot_home_search_hint)) },
                leadingIcon = { Icon(Lucide.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(if (items.isEmpty()) R.string.bot_home_empty else R.string.bot_home_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(shown, key = { it.bot.id.toString() }) { item ->
            BotListRow(
                item = item,
                now = now,
                onClick = { onOpen(item) },
                onTogglePin = { onTogglePin(item) },
                onToggleUnread = { onToggleUnread(item) },
                onHide = { onHide(item) },
                onDetail = { onDetail(item) },
                onSetMain = { onSetMain(item) },
                onDelete = { onDelete(item) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 80.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
        if (hiddenCount > 0) {
            item(key = "hidden") {
                TextButton(onClick = onManageHidden, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.bot_mode_hidden_count, hiddenCount))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotListRow(
    item: BotHomeItem,
    now: Instant,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleUnread: () -> Unit,
    onHide: () -> Unit,
    onDetail: () -> Unit,
    onSetMain: () -> Unit,
    onDelete: () -> Unit,
) {
    val bot = item.bot
    val row = item.row
    var menuOpen by remember(bot.id) { mutableStateOf(false) }
    val name = bot.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
    val locale = Locale.getDefault()
    val label = remember(row.updatedAt, now, locale) {
        botTimeLabel(row.updatedAt, now, weekdayName = { it.getDisplayName(TextStyle.SHORT, locale) })
    }
    val timeText = when (label.kind) {
        BotTimeLabelKind.NONE -> ""
        BotTimeLabelKind.YESTERDAY -> stringResource(R.string.bot_home_time_yesterday)
        else -> label.text
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantAvatar(assistant = bot, name = name, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (row.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.isMain) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.bot_home_main_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    } else if (row.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Lucide.Pin,
                            contentDescription = stringResource(R.string.bot_mode_pinned_badge),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.preview.ifBlank { stringResource(R.string.bot_home_no_messages) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    BotRowStatus(
                        generating = row.generating,
                        pendingApproval = row.pendingApproval,
                        unread = row.unread,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (!item.isMain) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (row.pinned) R.string.bot_mode_menu_unpin else R.string.bot_mode_menu_pin)) },
                    onClick = { menuOpen = false; onTogglePin() },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(stringResource(if (row.unread > 0) R.string.bot_home_menu_mark_read else R.string.bot_home_menu_mark_unread))
                },
                enabled = row.unread > 0 || row.latestConversationId != null,
                onClick = { menuOpen = false; onToggleUnread() },
            )
            if (!item.isMain) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bot_home_menu_set_main)) },
                    onClick = { menuOpen = false; onSetMain() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bot_mode_menu_hide)) },
                    onClick = { menuOpen = false; onHide() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bot_mode_menu_detail)) },
                onClick = { menuOpen = false; onDetail() },
            )
            // The current main bot cannot be deleted until another bot replaces it.
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.bot_home_menu_delete),
                        color = if (item.isMain) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.error,
                    )
                },
                enabled = !item.isMain,
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

/** Right-hand status: running spinner, then the approval tag, then the unread count. */
@Composable
private fun BotRowStatus(generating: Boolean, pendingApproval: Boolean, unread: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (generating) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = stringResource(R.string.bot_home_running),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (pendingApproval) {
            Text(
                text = stringResource(R.string.bot_home_pending_approval),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .widthIn(min = 20.dp)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (unread > 99) "99+" else unread.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        }
    }
}
