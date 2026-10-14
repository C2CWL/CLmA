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
import me.rerere.hugeicons.stroke.Book01
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
 * Rebuilt as a flat, one-scope-per-concern page: every row is one tap from its destination, and
 * no section is a dumping ground. The old "Models & Services" bucket held twenty unrelated entries
 * (model config, agent capabilities, external integrations, device permissions, diagnostics);
 * those now live in five sections that each answer a single question — what does the app look
 * like, which models does it talk to, what can the assistant do, what device access does it hold,
 * and what is it connected to.
 *
 * There is deliberately no intermediate "Preferences" page any more: it held five rows whose
 * subjects already belonged at the top level, and it duplicated the top-level "General" group it
 * sat next to.
 *
 * NOTE: [SettingsSearchIndex] keeps a hand-written mirror of the rows below. Add/remove/rename a
 * row here -> update that file too.
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

            // 1. Appearance & interaction — everything the user *sees and feels*.
            item("appearanceSettings") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_group_appearance)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesTheme) },
                        leadingContent = { Icon(HugeIcons.Sun01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_theme_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_theme)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesUI) },
                        leadingContent = { Icon(HugeIcons.View, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_ui_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_ui)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesGeneral) },
                        leadingContent = { Icon(HugeIcons.CursorPointer01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_general_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_general)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesNotification) },
                        leadingContent = { Icon(HugeIcons.Notification01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_notification_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_notification)) },
                    )
                }
            }

            // 2. Models & AI — what the assistant thinks with, and how the requests travel.
            item("modelSettings") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_group_models)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        leadingContent = { Icon(HugeIcons.AiMagic, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_default_model_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_default_model)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingProvider) },
                        leadingContent = { Icon(HugeIcons.Brain02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_providers_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSearch) },
                        leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_search_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSpeech) },
                        leadingContent = { Icon(HugeIcons.Megaphone01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tts_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_tts_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesNetwork) },
                        leadingContent = { Icon(HugeIcons.Internet, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_network_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_network)) },
                    )
                }
            }

            // 3. Assistant & automation — what the assistant is allowed to be and to do.
            item("assistantSettings") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_group_assistant)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Assistant) },
                        leadingContent = { Icon(HugeIcons.LookTop, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_assistant_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Extensions) },
                        leadingContent = { Icon(HugeIcons.Package, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_extensions_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_extensions)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSubAgents) },
                        leadingContent = { Icon(HugeIcons.Robot01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_sub_agents_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_sub_agents)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMcp) },
                        leadingContent = { Icon(HugeIcons.McpServer, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWorkflows) },
                        leadingContent = { Icon(HugeIcons.Connect, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_workflows_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_workflows)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingScheduledJobs) },
                        leadingContent = { Icon(HugeIcons.Clock02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_scheduled_jobs_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_scheduled_jobs)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingToolApprovals) },
                        leadingContent = { Icon(HugeIcons.Tick01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tool_approvals_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_tool_approvals)) },
                    )
                }
            }

            // 4. Device & permissions — the Android grants the automation rides on.
            item("deviceSettings") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_group_device)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingAccessibility) },
                        leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_accessibility_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_accessibility)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingFloatingBall) },
                        leadingContent = { Icon(HugeIcons.Message01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_floating_ball_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_floating_ball)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingNotifications) },
                        leadingContent = { Icon(HugeIcons.Notification01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_notifications_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_notifications)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPermissions) },
                        leadingContent = { Icon(HugeIcons.Shield01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_permissions_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_permissions)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingShizuku) },
                        leadingContent = { Icon(HugeIcons.Console, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_shizuku_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_shizuku)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTermux) },
                        leadingContent = { Icon(HugeIcons.ComputerTerminal01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_termux_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_termux)) },
                    )
                }
            }

            // 5. Connections — everything that reaches another machine.
            item("connectionSettings") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_group_connections)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingSsh) },
                        leadingContent = { Icon(HugeIcons.Link01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_ssh_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_ssh)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWeb) },
                        leadingContent = { Icon(HugeIcons.ServerStack01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTelegram) },
                        leadingContent = { Icon(HugeIcons.Telegram, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_telegram_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_telegram)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingBrowser) },
                        leadingContent = { Icon(HugeIcons.Earth, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_browser_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_browser)) },
                    )
                }
            }

            // 6. Data.
            item("dataSettings") {
                val storageState by produceState(-1 to 0L) {
                    value = filesManager.countChatFiles()
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_group_data)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Backup) },
                        leadingContent = { Icon(HugeIcons.Database02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_data_backup_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_data_backup)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingFiles) },
                        leadingContent = { Icon(HugeIcons.ImageUpload, null) },
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
                        headlineContent = { Text(stringResource(R.string.setting_page_chat_storage)) },
                    )
                }
            }

            // 7. About & diagnostics.
            item("aboutSettings") {
                val context = LocalContext.current
                val shareText = stringResource(R.string.setting_page_share_text)
                val share = stringResource(R.string.setting_page_share)
                val noShareApp = stringResource(R.string.setting_page_no_share_app)
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_group_about)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingDoctor) },
                        leadingContent = { Icon(HugeIcons.Wrench01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_doctor_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_doctor)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Log) },
                        leadingContent = { Icon(HugeIcons.Bookshelf01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_request_logs_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_request_logs)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAbout) },
                        leadingContent = { Icon(HugeIcons.Clapping01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_about_desc)) },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                var showQQGroupSheet by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = { showQQGroupSheet = true }
                                ) {
                                    Icon(
                                        imageVector = TencentQQIcon,
                                        contentDescription = stringResource(R.string.accessibility_qq),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                if (showQQGroupSheet) {
                                    QQGroupBottomSheet(
                                        onDismiss = { showQQGroupSheet = false }
                                    )
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
                        headlineContent = { Text(stringResource(R.string.setting_page_about)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSponsor) },
                        leadingContent = { Icon(HugeIcons.InLove, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_sponsor_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_sponsor)) },
                    )
                    item(
                        onClick = {
                            val docUrl = if (java.util.Locale.getDefault().language == "zh") {
                                "https://docs.rikka-ai.com/zh/introduction"
                            } else {
                                "https://docs.rikka-ai.com/introduction"
                            }
                            context.openUrl(docUrl)
                        },
                        leadingContent = { Icon(HugeIcons.Book01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_documentation_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_documentation)) },
                    )
                    item(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND)
                            intent.type = "text/plain"
                            intent.putExtra(Intent.EXTRA_TEXT, shareText)
                            try {
                                context.startActivity(Intent.createChooser(intent, share))
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, noShareApp, Toast.LENGTH_SHORT).show()
                            }
                        },
                        leadingContent = { Icon(HugeIcons.Share04, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_share_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_share)) },
                    )
                }
            }
        }
    }
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
