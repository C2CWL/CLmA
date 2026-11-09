package com.clma.app.ui.pages.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.data.botmode.botAssistants
import com.clma.app.data.botmode.botMembers
import com.clma.app.ui.components.ui.AssistantAvatar
import com.clma.app.ui.context.LocalNavController
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Send
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * One channel in Bot mode. The top bar shows the member avatars; the list shows each member's
 * real latest conversation (working / waiting for approval / last line). The box at the bottom
 * sends a message to the @-mentioned members, or to all of them, each in its own conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotChannelPage(channelId: String) {
    val vm: BotChannelVM = koinViewModel(key = "bot-channel-$channelId", parameters = { parametersOf(channelId) })
    val nav = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val channel by vm.channel.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val lastResult by vm.lastResult.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }

    val ch = channel
    if (ch == null) {
        // Deleted (here or elsewhere): nothing to show.
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bot_home_tab_channels)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        }) { padding ->
            Text(
                stringResource(R.string.bot_channel_gone),
                modifier = Modifier.padding(padding).padding(24.dp),
            )
        }
        return
    }
    val members = ch.botMembers(settings)
    val targets = vm.targetsFor(draft, members)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                "# " + ch.name.ifBlank { stringResource(R.string.bot_channel_untitled) },
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.bot_channel_members_count, members.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        MemberAvatarStack(members, size = 26.dp)
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Lucide.EllipsisVertical, contentDescription = stringResource(R.string.bot_channel_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bot_channel_edit)) },
                            onClick = { menuOpen = false; editing = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bot_mode_channels_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    lastResult?.let { r ->
                        val line = buildString {
                            if (r.sentTo.isNotEmpty()) append(stringResource(R.string.bot_channel_sent_to, r.sentTo.joinToString("、")))
                            if (r.failed.isNotEmpty()) {
                                if (isNotEmpty()) append("  ")
                                append(stringResource(R.string.bot_channel_send_failed, r.failed.joinToString("、")))
                            }
                        }
                        if (line.isNotEmpty()) {
                            Text(
                                line,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        if (members.isEmpty()) {
                            stringResource(R.string.bot_channel_no_members)
                        } else {
                            stringResource(R.string.bot_channel_send_targets, targets.joinToString("、") { it.name })
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder = { Text(stringResource(R.string.bot_channel_input_hint)) },
                            modifier = Modifier.weight(1f),
                            maxLines = 5,
                            enabled = members.isNotEmpty(),
                        )
                        Spacer(Modifier.width(8.dp))
                        if (sending) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            FilledIconButton(
                                enabled = draft.isNotBlank() && targets.isNotEmpty(),
                                onClick = {
                                    vm.send(draft)
                                    draft = ""
                                },
                            ) {
                                Icon(Lucide.Send, contentDescription = stringResource(R.string.bot_channel_send))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (ch.description.isNotBlank()) {
                item(key = "desc") {
                    Text(
                        ch.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            item(key = "hint") {
                Text(
                    stringResource(R.string.bot_channel_how_it_works),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            items(rows, key = { it.bot.id.toString() }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.openMember(row) { conv ->
                                nav.navigate(Screen.BotChat(row.bot.id.toString(), conv.toString()))
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistantAvatar(assistant = row.bot, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.bot.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                botTimeText(row.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            when {
                                row.generating -> stringResource(R.string.bot_home_running)
                                row.pendingApproval -> stringResource(R.string.bot_home_pending_approval)
                                row.preview.isNotBlank() -> row.preview
                                else -> stringResource(R.string.bot_channel_member_no_chat)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.pendingApproval) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(start = 68.dp))
            }
        }
    }

    if (editing) {
        BotChannelEditDialog(
            initial = ch,
            bots = settings.botAssistants(),
            onDismiss = { editing = false },
            onSave = {
                editing = false
                vm.save(it)
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.bot_channel_delete_title)) },
            text = { Text(stringResource(R.string.bot_channel_delete_confirm, ch.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete { nav.popBackStack() }
                }) { Text(stringResource(R.string.bot_mode_channels_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.bot_home_cancel)) } },
        )
    }
}
