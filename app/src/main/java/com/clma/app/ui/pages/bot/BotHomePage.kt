package com.clma.app.ui.pages.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import com.clma.app.data.botmode.botsHiddenForManage
import com.clma.app.ui.pages.assistant.AssistantVM
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.ui.context.LocalNavController
import com.clma.app.data.botmode.botAssistants
import com.clma.app.data.botmode.BotPlace
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Settings as LucideSettings
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

private enum class BotHomeTab { BOTS, CHANNELS, BOARD }

/**
 * Bot mode root, separate from the classic chat page and drawer: a chat-app style bot list,
 * channels, a team board, and a settings shortcut in a bottom bar. Classic mode never reaches this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotHomePage(vm: BotHomeVM = koinViewModel()) {
    val nav = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(BotHomeTab.BOTS) }
    val assistantVm: AssistantVM = koinViewModel()
    var showNewBot by remember { mutableStateOf(false) }
    var showNewChannel by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BotHomeItem?>(null) }
    var selectedPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    val places = settings.displaySetting.botPlaces
    val selectedPlace = places.firstOrNull { it.id.toString() == selectedPlaceId }
    var editorId by remember { mutableStateOf<Uuid?>(null) }
    var editingPlace by remember { mutableStateOf<BotPlace?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (tab) {
                            BotHomeTab.BOTS -> stringResource(R.string.bot_home_title)
                            BotHomeTab.CHANNELS -> stringResource(R.string.bot_home_tab_channels)
                            BotHomeTab.BOARD -> stringResource(R.string.bot_board_heading)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Screen.BotSettings) }) {
                        Icon(Lucide.LucideSettings, contentDescription = stringResource(R.string.bot_settings_title))
                    }
                    if (tab == BotHomeTab.BOTS) {
                        IconButton(onClick = { showNewBot = true }) {
                            Icon(Lucide.Plus, contentDescription = stringResource(R.string.bot_home_new_bot))
                        }
                    } else if (tab == BotHomeTab.CHANNELS) {
                        IconButton(onClick = { showNewChannel = true }) {
                            Icon(Lucide.Plus, contentDescription = stringResource(R.string.bot_channel_new))
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == BotHomeTab.BOTS,
                    onClick = { tab = BotHomeTab.BOTS },
                    icon = { Icon(Lucide.Bot, contentDescription = null) },
                    label = { Text(stringResource(R.string.bot_home_tab_bots)) },
                )
                NavigationBarItem(
                    selected = tab == BotHomeTab.CHANNELS,
                    onClick = { tab = BotHomeTab.CHANNELS },
                    icon = { Icon(Lucide.Users, contentDescription = null) },
                    label = { Text(stringResource(R.string.bot_home_tab_channels)) },
                )
                NavigationBarItem(
                    selected = tab == BotHomeTab.BOARD,
                    onClick = { tab = BotHomeTab.BOARD },
                    icon = { Icon(Lucide.LayoutGrid, contentDescription = null) },
                    label = { Text(stringResource(R.string.bot_board_tab)) },
                )
                NavigationBarItem(
                    // Settings is the existing settings hub, pushed on top; the tab never stays selected.
                    selected = false,
                    onClick = { nav.navigate(Screen.Setting) },
                    icon = { Icon(Lucide.LucideSettings, contentDescription = null) },
                    label = { Text(stringResource(R.string.bot_home_tab_settings)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            BotPlacesNavigation(
                places = places,
                selected = selectedPlace,
                onSelect = { selectedPlaceId = it?.id?.toString() },
                onAdd = {
                    editingPlace = null
                    editorId = Uuid.random()
                },
                onEdit = {
                    editingPlace = it
                    editorId = it.id
                },
                enabled = !settings.init,
            )
            when (tab) {
                BotHomeTab.BOTS -> BotListTab(
                    items = selectedPlace?.let { place -> items.filter { it.bot.id in place.botIds } } ?: items,
                    hiddenCount = settings.botsHiddenForManage().size,
                    onOpen = { item ->
                        vm.open(item.row) { conversationId ->
                            nav.navigate(Screen.BotChat(item.bot.id.toString(), conversationId.toString()))
                        }
                    },
                    onTogglePin = { vm.togglePin(it.bot) },
                    onToggleUnread = { vm.toggleUnread(it.row) },
                    onHide = { vm.hide(it.bot) },
                    onDetail = { nav.navigate(Screen.BotInfo(botId = it.bot.id.toString())) },
                    onSetMain = { vm.setMain(it.bot) },
                    onDelete = { deleting = it },
                    onManageHidden = { nav.navigate(Screen.SettingChatLayout) },
                    modifier = Modifier.weight(1f),
                )

                BotHomeTab.BOARD -> BotBoardTab(
                    bots = settings.botAssistants(),
                    modifier = Modifier.weight(1f),
                )

                BotHomeTab.CHANNELS -> BotChannelsTab(
                    settings = settings,
                    channels = settings.displaySetting.botChannels.filter { selectedPlace == null || it.id in selectedPlace.channelIds },
                    onOpen = { channel -> nav.navigate(Screen.BotChannel(channel.id.toString())) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    editorId?.let { id ->
        val initial = editingPlace
        BotPlaceEditor(
            draftId = id,
            initial = initial,
            settings = settings,
            onDismiss = { editorId = null },
            onSave = { draft, onResult ->
                vm.savePlace(initial, draft) { saved ->
                    onResult(saved)
                    if (saved) {
                        selectedPlaceId = id.toString()
                        editorId = null
                    }
                }
            },
            onRemove = {
                vm.removePlace(it)
                selectedPlaceId = null
                editorId = null
            },
        )
    }

    if (showNewBot) {
        NewBotDialog(
            onDismiss = { showNewBot = false },
            onCreate = { name, prompt, makeMain ->
                showNewBot = false
                vm.createBot(name, prompt, makeMain, selectedPlace?.id) { id ->
                    // Avatar, model and the rest are set on the bot's info page.
                    nav.navigate(Screen.BotInfo(botId = id.toString()))
                }
            },
        )
    }

    if (showNewChannel) {
        BotChannelEditDialog(
            initial = null,
            bots = settings.botAssistants(),
            onDismiss = { showNewChannel = false },
            onSave = { channel ->
                showNewChannel = false
                vm.createChannel(channel, selectedPlace?.id) {
                    nav.navigate(Screen.BotChannel(channel.id.toString()))
                }
            },
        )
    }

    deleting?.let { item ->
        val name = item.bot.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.bot_home_delete_title)) },
            text = { Text(stringResource(R.string.bot_home_delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    assistantVm.removeAssistant(item.bot)
                }) {
                    Text(stringResource(R.string.bot_home_menu_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.bot_home_cancel)) }
            },
        )
    }
}

@Composable
private fun NewBotDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, systemPrompt: String, makeMain: Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var makeMain by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bot_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.bot_new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.bot_new_prompt)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = makeMain, onCheckedChange = { makeMain = it })
                    Text(stringResource(R.string.bot_new_set_main), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    stringResource(R.string.bot_new_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, prompt, makeMain) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.bot_new_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bot_home_cancel)) }
        },
    )
}
