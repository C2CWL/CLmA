package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Console
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Developer
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Telegram
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tiktok
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsHubStyle
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.icons.DiscordIcon
import me.rerere.rikkahub.ui.components.ui.icons.TencentQQIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.joinQQGroup
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * The settings hub.
 *
 * Rows and grouping come from [settingsHubSections] / [settingsHubItems]. [SettingsHubStyle]
 * only swaps labels and which section owns Network — destinations never fork. Icons and the
 * Share / About trailing actions stay here so Compose stays out of the catalog.
 *
 * Search index hub rows are derived from the same catalog (see [settingsHubSearchRows]).
 */
@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            navController.navigate(Screen.SettingsSearch)
                        }
                    ) {
                        Icon(HugeIcons.GlobalSearch, stringResource(R.string.accessibility_search))
                    }
                    if (settings.developerMode) {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.Developer)
                            }
                        ) {
                            Icon(HugeIcons.Developer, stringResource(R.string.accessibility_developer_options))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val hubStyle = settings.displaySetting.settingsHubStyle
        val sections = remember(hubStyle) { settingsHubSections(hubStyle) }
        val shareText = stringResource(R.string.setting_page_share_text)
        val shareLabel = stringResource(R.string.setting_page_share)
        val noShareApp = stringResource(R.string.setting_page_no_share_app)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            }

            sections.forEach { section ->
                item(section.id.name) {
                    // Files row needs live storage stats; compute only when that section is shown.
                    val needsStorage = section.items.contains(SettingsHubItemId.FILES)
                    val storageState by produceState(-1 to 0L, needsStorage) {
                        if (needsStorage) {
                            value = filesManager.countChatFiles()
                        }
                    }
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(section.titleRes(hubStyle))) },
                    ) {
                        section.items.forEach { itemId ->
                            val def = settingsHubItems.getValue(itemId)
                            val copy = def.copyFor(hubStyle)
                            val icon = hubItemIcon(itemId)
                            when (itemId) {
                                SettingsHubItemId.FILES -> {
                                    item(
                                        onClick = { navController.navigate(Screen.SettingFiles) },
                                        leadingContent = { Icon(icon, null) },
                                        supportingContent = {
                                            if (storageState.first == -1) {
                                                Text(stringResource(R.string.calculating))
                                            } else {
                                                Text(
                                                    stringResource(
                                                        R.string.setting_page_chat_storage_desc,
                                                        storageState.first,
                                                        storageState.second / 1024 / 1024.0
                                                    )
                                                )
                                            }
                                        },
                                        headlineContent = { Text(stringResource(copy.titleRes)) },
                                    )
                                }
                                SettingsHubItemId.ABOUT -> {
                                    item(
                                        onClick = { navController.navigate(Screen.SettingAbout) },
                                        leadingContent = { Icon(icon, null) },
                                        supportingContent = {
                                            copy.descriptionRes?.let { Text(stringResource(it)) }
                                        },
                                        trailingContent = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                var showQQGroupSheet by remember { mutableStateOf(false) }
                                                IconButton(onClick = { showQQGroupSheet = true }) {
                                                    Icon(
                                                        imageVector = TencentQQIcon,
                                                        contentDescription = stringResource(R.string.accessibility_qq),
                                                        tint = MaterialTheme.colorScheme.secondary
                                                    )
                                                }
                                                if (showQQGroupSheet) {
                                                    QQGroupBottomSheet(onDismiss = { showQQGroupSheet = false })
                                                }
                                                IconButton(
                                                    onClick = {
                                                        context.openUrl("https://discord.gg/9weBqxe5c4")
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = DiscordIcon,
                                                        contentDescription = stringResource(R.string.accessibility_discord),
                                                        tint = MaterialTheme.colorScheme.secondary
                                                    )
                                                }
                                            }
                                        },
                                        headlineContent = { Text(stringResource(copy.titleRes)) },
                                    )
                                }
                                SettingsHubItemId.SHARE -> {
                                    item(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_SEND)
                                            intent.type = "text/plain"
                                            intent.putExtra(Intent.EXTRA_TEXT, shareText)
                                            try {
                                                context.startActivity(Intent.createChooser(intent, shareLabel))
                                            } catch (e: ActivityNotFoundException) {
                                                Toast.makeText(context, noShareApp, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        leadingContent = { Icon(icon, null) },
                                        supportingContent = {
                                            copy.descriptionRes?.let { Text(stringResource(it)) }
                                        },
                                        headlineContent = { Text(stringResource(copy.titleRes)) },
                                    )
                                }
                                else -> {
                                    val route = def.route
                                    item(
                                        onClick = {
                                            if (route != null) navController.navigate(route)
                                        },
                                        leadingContent = { Icon(icon, null) },
                                        supportingContent = {
                                            copy.descriptionRes?.let { Text(stringResource(it)) }
                                        },
                                        headlineContent = { Text(stringResource(copy.titleRes)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


private fun hubItemIcon(id: SettingsHubItemId): ImageVector = when (id) {
    SettingsHubItemId.THEME -> HugeIcons.Sun01
    SettingsHubItemId.UI -> HugeIcons.View
    SettingsHubItemId.GENERAL -> HugeIcons.CursorPointer01
    SettingsHubItemId.NOTIF_PREFS -> HugeIcons.Notification01
    SettingsHubItemId.MODELS -> HugeIcons.AiMagic
    SettingsHubItemId.PROVIDERS -> HugeIcons.Brain02
    SettingsHubItemId.SEARCH -> HugeIcons.GlobalSearch
    SettingsHubItemId.SPEECH -> HugeIcons.Megaphone01
    SettingsHubItemId.NETWORK -> HugeIcons.Internet
    SettingsHubItemId.ASSISTANT -> HugeIcons.LookTop
    SettingsHubItemId.EXTENSIONS -> HugeIcons.Package
    SettingsHubItemId.SUB_AGENTS -> HugeIcons.Robot01
    SettingsHubItemId.MCP -> HugeIcons.McpServer
    SettingsHubItemId.WORKFLOWS -> HugeIcons.Connect
    SettingsHubItemId.SCHEDULED -> HugeIcons.Clock02
    SettingsHubItemId.TOOL_APPROVALS -> HugeIcons.Tick01
    SettingsHubItemId.ACCESSIBILITY -> HugeIcons.SmartPhone01
    SettingsHubItemId.FLOATING_BALL -> HugeIcons.Message01
    SettingsHubItemId.NOTIF_ACCESS -> HugeIcons.Notification01
    SettingsHubItemId.PERMISSIONS -> HugeIcons.Shield01
    SettingsHubItemId.SHIZUKU -> HugeIcons.Console
    SettingsHubItemId.TERMUX -> HugeIcons.ComputerTerminal01
    SettingsHubItemId.SSH -> HugeIcons.Link01
    SettingsHubItemId.SECRETS -> HugeIcons.Lock
    SettingsHubItemId.WEB -> HugeIcons.ServerStack01
    SettingsHubItemId.TELEGRAM -> HugeIcons.Telegram
    SettingsHubItemId.BROWSER -> HugeIcons.Earth
    SettingsHubItemId.BACKUP -> HugeIcons.Database02
    SettingsHubItemId.FILES -> HugeIcons.ImageUpload
    SettingsHubItemId.DOCTOR -> HugeIcons.Wrench01
    SettingsHubItemId.LOGS -> HugeIcons.Bookshelf01
    SettingsHubItemId.ABOUT -> HugeIcons.Clapping01
    SettingsHubItemId.SHARE -> HugeIcons.Share04
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Card(
        modifier = Modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            ListItem(
                supportingContent = {
                    Text(stringResource(R.string.setting_page_config_api_desc))
                },
                leadingContent = {
                    Icon(HugeIcons.Alert01, null)
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            ) {
                Text(stringResource(R.string.setting_page_config_api_title))
            }

            TextButton(
                onClick = {
                    navController.navigate(Screen.SettingProvider)
                }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}

private data class QQGroup(
    val name: String,
    val key: String? = null,
    val number: String? = null,
    val icon: ImageVector = TencentQQIcon,
)

private val QQ_GROUPS = listOf(
    QQGroup("RikkaHub 一群", "4POE46u9e_zoy1TkNfWdCvueR9CKFJdk"),
    QQGroup("RikkaHub 二群", "Qsm0whzbPsm1UyNpR683ulLyMZ2Pqrw0"),
    QQGroup("RikkaHub 三群", "Qc9oP-9tXioZeQEvEvI2_owWtBAIx3lS"),
    QQGroup("抖音一群", number = "569655479852", icon = HugeIcons.Tiktok),
)

@Composable
private fun QQGroupBottomSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QQ_GROUPS.forEach { group ->
                ListItem(
                    onClick = {
                        if (group.number != null) {
                            context.writeClipboardText(group.number)
                            Toast.makeText(context, "群号已复制", Toast.LENGTH_SHORT).show()
                        } else {
                            context.joinQQGroup(group.key)
                        }
                        onDismiss()
                    },
                    supportingContent = group.number?.let { number ->
                        { Text(number) }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = group.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    },
                ) {
                    Text(group.name)
                }
            }
        }
    }
}
