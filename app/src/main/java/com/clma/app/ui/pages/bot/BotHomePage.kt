package com.clma.app.ui.pages.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.clma.app.ui.pages.chat.botmode.BotChannelsCard
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Settings as LucideSettings
import org.koin.androidx.compose.koinViewModel

private enum class BotHomeTab { BOTS, CHANNELS }

/**
 * Bot mode root, separate from the classic chat page and drawer: a chat-app style bot list,
 * channels, and a settings shortcut in a bottom bar. Classic mode never reaches this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotHomePage(vm: BotHomeVM = koinViewModel()) {
    val nav = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(BotHomeTab.BOTS) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (tab == BotHomeTab.BOTS) {
                            stringResource(R.string.bot_home_title)
                        } else {
                            stringResource(R.string.bot_home_tab_channels)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    if (tab == BotHomeTab.BOTS) {
                        IconButton(onClick = { nav.navigate(Screen.Assistant) }) {
                            Icon(Lucide.Plus, contentDescription = stringResource(R.string.bot_home_new_bot))
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
                    // Settings is the existing settings hub, pushed on top; the tab never stays selected.
                    selected = false,
                    onClick = { nav.navigate(Screen.Setting) },
                    icon = { Icon(Lucide.LucideSettings, contentDescription = null) },
                    label = { Text(stringResource(R.string.bot_home_tab_settings)) },
                )
            }
        },
    ) { padding ->
        when (tab) {
            BotHomeTab.BOTS -> BotListTab(
                items = items,
                hiddenCount = settings.displaySetting.botHiddenAssistantIds.count { id ->
                    settings.assistants.any { it.id == id }
                },
                onOpen = { item ->
                    vm.open(item.row) { conversationId ->
                        nav.navigate(Screen.Chat(conversationId.toString()))
                    }
                },
                onTogglePin = { vm.togglePin(it.bot) },
                onToggleUnread = { vm.toggleUnread(it.row) },
                onHide = { vm.hide(it.bot) },
                onDetail = { nav.navigate(Screen.AssistantDetail(id = it.bot.id.toString())) },
                onManageHidden = { nav.navigate(Screen.SettingChatLayout) },
                modifier = Modifier.padding(padding),
            )

            BotHomeTab.CHANNELS -> Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // PR3 replaces this with a real channel list (member avatars in the top bar).
                BotChannelsCard(
                    settings = settings,
                    displaySetting = settings.displaySetting,
                    updateDisplaySetting = { vm.updateDisplaySetting(it) },
                )
                Text(
                    text = stringResource(R.string.bot_home_channels_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}
