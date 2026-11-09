package com.clma.app.ui.pages.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.botmode.botMembers
import com.clma.app.data.datastore.BotChannel
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.ui.components.ui.AssistantAvatar
import kotlin.uuid.Uuid

/** The Channels tab of the Bot home: every channel with its member avatars. */
@Composable
fun BotChannelsTab(
    settings: Settings,
    onOpen: (BotChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val channels = settings.displaySetting.botChannels
    if (channels.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.bot_channel_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.bot_channel_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(channels, key = { it.id.toString() }) { channel ->
            val members = channel.botMembers(settings)
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(channel) }.padding(horizontal = 4.dp),
                headlineContent = {
                    Text(
                        channel.name.ifBlank { stringResource(R.string.bot_channel_untitled) },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        channel.description.ifBlank { stringResource(R.string.bot_channel_members_count, members.size) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = { MemberAvatarStack(members, size = 28.dp) },
                leadingContent = {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "#",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                },
            )
            HorizontalDivider(Modifier.padding(start = 76.dp))
        }
    }
}

/** Overlapping member avatars (up to [max], then "+n"). */
@Composable
fun MemberAvatarStack(members: List<Assistant>, size: Dp, max: Int = 4) {
    val shown = members.take(max)
    val overlap = size / 3
    Row(verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { index, bot ->
            Box(Modifier.offset(x = -overlap * index)) {
                AssistantAvatar(assistant = bot, modifier = Modifier.size(size))
            }
        }
        if (members.size > max) {
            Text(
                "+${members.size - max}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.offset(x = -overlap * shown.size + 4.dp),
            )
        }
    }
}

/** Create or edit a channel. Members come from the Bot library only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotChannelEditDialog(
    initial: BotChannel?,
    bots: List<Assistant>,
    onDismiss: () -> Unit,
    onSave: (BotChannel) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var desc by rememberSaveable { mutableStateOf(initial?.description.orEmpty()) }
    val botIds = remember(bots) { bots.map { it.id }.toSet() }
    var members by remember { mutableStateOf(initial?.memberAssistantIds.orEmpty().filter { it in botIds }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.bot_channel_new else R.string.bot_channel_edit))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.bot_mode_channels_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text(stringResource(R.string.bot_mode_channels_desc_hint)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.bot_mode_channels_pick_members), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bots.forEach { bot ->
                        val selected = bot.id in members
                        FilterChip(
                            selected = selected,
                            onClick = { members = if (selected) members - bot.id else members + bot.id },
                            leadingIcon = { AssistantAvatar(assistant = bot, modifier = Modifier.size(20.dp)) },
                            label = { Text(bot.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    // Keep the bot order of the library for a stable avatar row.
                    val ordered = bots.map { it.id }.filter { it in members }
                    onSave(
                        (initial ?: BotChannel(id = Uuid.random())).copy(
                            name = name.trim(),
                            description = desc.trim(),
                            memberAssistantIds = ordered,
                        )
                    )
                },
            ) { Text(stringResource(R.string.bot_mode_channels_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bot_home_cancel)) } },
    )
}
