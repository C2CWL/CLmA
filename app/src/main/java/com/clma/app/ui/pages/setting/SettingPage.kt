package com.clma.app.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Console
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Developer
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.LayoutTop
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Palette
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Telegram
import me.rerere.hugeicons.stroke.TextFont
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Timer01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.hugeicons.stroke.Zap
import com.clma.app.BuildConfig
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.data.ai.tools.ApprovalMode
import com.clma.app.data.datastore.ChatFontFamily
import com.clma.app.data.datastore.ChatLayout
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.effectiveChatLayout
import com.clma.app.data.datastore.getCurrentChatModel
import com.clma.app.data.datastore.isNotConfigured
import com.clma.app.data.files.FilesManager
import com.clma.app.data.preferences.ToolApprovalPreferences
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.context.Navigator
import com.clma.app.ui.hooks.rememberColorMode
import com.clma.app.ui.pages.setting.termux.SettingTermuxViewModel
import com.clma.app.ui.theme.CustomColors
import com.clma.app.ui.theme.VisualStyle
import com.clma.app.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * The settings hub: a search box on top, then the groups from [settingsHubSections]. Each row is
 * icon + title + a short live status + chevron; descriptions are not shown (they stay in the
 * catalog for the search). All colours come from [MaterialTheme.colorScheme], so the Claude /
 * Gemini palettes (视觉风格) and dark mode apply without special cases.
 */
@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val statusSource = rememberHubStatusSource()

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
        val shareText = stringResource(R.string.setting_page_share_text)
        val shareLabel = stringResource(R.string.setting_page_share)
        val noShareApp = stringResource(R.string.setting_page_no_share_app)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item("search") {
                HubSearchBox(onClick = { navController.navigate(Screen.SettingsSearch) })
            }

            if (settings.isNotConfigured()) {
                item("provider_warning") {
                    ProviderConfigWarningCard(navController)
                }
            }

            settingsHubSections.forEach { section ->
                item("title_${section.id.name}") {
                    Text(
                        text = stringResource(section.titleRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 8.dp),
                    )
                }
                item(section.id.name) {
                    HubGroupCard {
                        section.items.forEachIndexed { index, itemId ->
                            if (index > 0) HubRowDivider()
                            val def = settingsHubItems.getValue(itemId)
                            HubRow(
                                icon = hubItemIcon(itemId),
                                title = stringResource(def.titleRes),
                                summary = hubItemSummary(itemId, settings, statusSource),
                                onClick = {
                                    when (itemId) {
                                        SettingsHubItemId.SHARE -> {
                                            val intent = Intent(Intent.ACTION_SEND)
                                            intent.type = "text/plain"
                                            intent.putExtra(Intent.EXTRA_TEXT, shareText)
                                            try {
                                                context.startActivity(Intent.createChooser(intent, shareLabel))
                                            } catch (e: ActivityNotFoundException) {
                                                Toast.makeText(context, noShareApp, Toast.LENGTH_SHORT).show()
                                            }
                                        }

                                        else -> def.route?.let { navController.navigate(it) }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item("bottom_spacer") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private val HubCardShape = RoundedCornerShape(20.dp)
private val HubIconSize = 36.dp
private val HubRowStartPadding = 14.dp
private val HubRowGap = 14.dp

/** Pill-shaped entry into the existing settings search page. */
@Composable
private fun HubSearchBox(onClick: () -> Unit) {
    val hint = stringResource(R.string.setting_hub_search_hint)
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.GlobalSearch,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One group: a 20dp rounded card on the surface-container tone. */
@Composable
private fun HubGroupCard(content: @Composable () -> Unit) {
    Surface(
        shape = HubCardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/** Thin divider between rows, starting after the icon so the icons read as one column. */
@Composable
private fun HubRowDivider() {
    HorizontalDivider(
        thickness = Dp.Hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = HubRowStartPadding + HubIconSize + HubRowGap, end = 16.dp),
    )
}

@Composable
private fun HubRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(start = HubRowStartPadding, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(HubIconSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(HubRowGap))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!summary.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            HugeIcons.ArrowRight01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Live values the summaries need that are not in [Settings]. */
private class HubStatusSource(
    val storage: Pair<Int, Long>,
    val approvalMode: ApprovalMode,
    val turnBudgetMinutes: Long,
    val maxToolSteps: Int,
    val notificationAccess: Boolean,
    val colorModeLabel: String,
)

@Composable
private fun rememberHubStatusSource(): HubStatusSource {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val approvalPrefs: ToolApprovalPreferences = koinInject()
    // Turn budget and tool steps are stored in TermuxPreferences; read them the same way the
    // 运行与上下文 page does.
    val termuxVm: SettingTermuxViewModel = koinViewModel()
    val termuxConfig by termuxVm.config.collectAsStateWithLifecycle()
    val approvalMode by approvalPrefs.modeFlow.collectAsStateWithLifecycle(initialValue = ApprovalMode.MANUAL)
    val storage by produceState(-1 to 0L) { value = filesManager.countChatFiles() }
    val colorMode by rememberColorMode()
    var notificationAccess by remember { mutableStateOf(false) }
    // Re-checked each time the hub comes back to the front (the user may have just granted it).
    LifecycleResumeEffect(context) {
        notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        onPauseOrDispose { }
    }
    return HubStatusSource(
        storage = storage,
        approvalMode = approvalMode,
        turnBudgetMinutes = termuxConfig.turnBudgetMs / 60_000L,
        maxToolSteps = termuxConfig.maxToolSteps,
        notificationAccess = notificationAccess,
        colorModeLabel = colorModeLabel(colorMode),
    )
}

/** Right-side status of a hub row, or null when there is nothing short and useful to show. */
@Composable
private fun hubItemSummary(id: SettingsHubItemId, settings: Settings, status: HubStatusSource): String? {
    @Composable
    fun count(n: Int): String =
        if (n == 0) stringResource(R.string.setting_hub_summary_none)
        else stringResource(R.string.setting_hub_summary_count, n)

    @Composable
    fun onOff(on: Boolean): String =
        stringResource(if (on) R.string.setting_hub_summary_on else R.string.setting_hub_summary_off)

    val display = settings.displaySetting
    return when (id) {
        SettingsHubItemId.PROVIDERS -> {
            val enabled = settings.providers.count { it.enabled }
            if (enabled == 0) stringResource(R.string.setting_hub_summary_none)
            else stringResource(R.string.setting_hub_summary_enabled_count, enabled)
        }
        SettingsHubItemId.MODELS -> settings.getCurrentChatModel()?.let { it.displayName.ifBlank { it.modelId } }
            ?: stringResource(R.string.setting_hub_summary_not_selected)
        SettingsHubItemId.ASSISTANT -> count(settings.assistants.size)
        SettingsHubItemId.PROMPTS -> count(settings.modeInjections.size + settings.lorebooks.size)
        SettingsHubItemId.SEARCH -> count(settings.searchServices.size)
        SettingsHubItemId.SPEECH -> count(settings.ttsProviders.size + settings.asrProviders.size)
        SettingsHubItemId.MCP -> count(settings.mcpServers.size)
        SettingsHubItemId.SUB_AGENTS ->
            stringResource(R.string.setting_hub_summary_concurrency, settings.subAgentGlobalConcurrencyCap)
        SettingsHubItemId.TOOL_APPROVALS -> stringResource(
            when (status.approvalMode) {
                ApprovalMode.AUTO -> R.string.setting_page_tool_approvals_mode_auto
                ApprovalMode.SEMI -> R.string.setting_page_tool_approvals_mode_semi
                ApprovalMode.MANUAL -> R.string.setting_page_tool_approvals_mode_manual
            }
        )
        SettingsHubItemId.RUNTIME -> stringResource(
            R.string.setting_hub_summary_runtime,
            status.maxToolSteps,
            status.turnBudgetMinutes.toInt(),
        )
        SettingsHubItemId.NETWORK -> stringResource(
            if (settings.networkSetting.proxyUrl.isBlank()) R.string.setting_hub_summary_direct
            else R.string.setting_hub_summary_proxy
        )
        SettingsHubItemId.NOTIF_ACCESS -> stringResource(
            if (status.notificationAccess) R.string.setting_hub_summary_granted
            else R.string.setting_hub_summary_not_granted
        )
        SettingsHubItemId.FLOATING_BALL -> onOff(settings.floatingBallEnabled)
        SettingsHubItemId.WEB -> onOff(settings.webServerEnabled)
        SettingsHubItemId.VISUAL_STYLE ->
            stringResource(VisualStyle.current(settings.themeId, settings.dynamicColor).nameRes)
        SettingsHubItemId.COLOR_MODE -> status.colorModeLabel
        SettingsHubItemId.FONTS -> {
            val standard = display.fontSizeRatio == 1f && display.uiFontScale == 1f &&
                display.chatFontFamily == ChatFontFamily.DEFAULT && display.uiFontFamily == ChatFontFamily.DEFAULT
            stringResource(if (standard) R.string.setting_hub_summary_standard else R.string.setting_hub_summary_custom)
        }
        SettingsHubItemId.CHAT_LAYOUT -> stringResource(
            when (display.effectiveChatLayout) {
                ChatLayout.ORIGINAL -> R.string.setting_display_page_chat_layout_original
                ChatLayout.CLEAN -> R.string.setting_display_page_chat_layout_clean
                ChatLayout.CLAUDE -> R.string.setting_display_page_chat_layout_claude
            }
        )
        SettingsHubItemId.INPUT -> stringResource(
            if (display.sendOnEnter) R.string.setting_hub_summary_send_on_enter
            else R.string.setting_hub_summary_enter_newline
        )
        SettingsHubItemId.QUICK_MESSAGES -> count(settings.quickMessages.size)
        SettingsHubItemId.NOTIF_PREFS ->
            onOff(display.enableNotificationOnMessageGeneration || display.enableLiveUpdateNotification)
        SettingsHubItemId.BACKUP -> stringResource(
            if (settings.backupReminderConfig.enabled) R.string.setting_hub_summary_reminder_on
            else R.string.setting_hub_summary_reminder_off
        )
        SettingsHubItemId.FILES -> if (status.storage.first < 0) {
            stringResource(R.string.calculating)
        } else {
            stringResource(
                R.string.setting_hub_summary_files,
                status.storage.first,
                status.storage.second / 1024 / 1024.0,
            )
        }
        SettingsHubItemId.LOGS -> stringResource(
            if (settings.recordRequests) R.string.setting_hub_summary_recording
            else R.string.setting_hub_summary_not_recording
        )
        SettingsHubItemId.ABOUT -> "v${BuildConfig.VERSION_NAME}"
        // No cheap, reliable status for these (they need a database read, a service probe or an
        // external app check), so the row shows only its chevron.
        SettingsHubItemId.WORKSPACES,
        SettingsHubItemId.SKILLS,
        SettingsHubItemId.TERMUX,
        SettingsHubItemId.SSH,
        SettingsHubItemId.BROWSER,
        SettingsHubItemId.SECRETS,
        SettingsHubItemId.PERMISSIONS,
        SettingsHubItemId.ACCESSIBILITY,
        SettingsHubItemId.SHIZUKU,
        SettingsHubItemId.WORKFLOWS,
        SettingsHubItemId.SCHEDULED,
        SettingsHubItemId.TELEGRAM,
        SettingsHubItemId.MESSAGE_DISPLAY,
        SettingsHubItemId.DOCTOR,
        SettingsHubItemId.SHARE -> null
    }
}

private fun hubItemIcon(id: SettingsHubItemId): ImageVector = when (id) {
    SettingsHubItemId.PROVIDERS -> HugeIcons.Brain02
    SettingsHubItemId.MODELS -> HugeIcons.AiMagic
    SettingsHubItemId.ASSISTANT -> HugeIcons.LookTop
    SettingsHubItemId.PROMPTS -> HugeIcons.Book03
    SettingsHubItemId.SEARCH -> HugeIcons.GlobalSearch
    SettingsHubItemId.SPEECH -> HugeIcons.Megaphone01
    SettingsHubItemId.WORKSPACES -> HugeIcons.Folder01
    SettingsHubItemId.SKILLS -> HugeIcons.Puzzle
    SettingsHubItemId.MCP -> HugeIcons.McpServer
    SettingsHubItemId.SUB_AGENTS -> HugeIcons.Robot01
    SettingsHubItemId.TOOL_APPROVALS -> HugeIcons.Tick01
    SettingsHubItemId.RUNTIME -> HugeIcons.Timer01
    SettingsHubItemId.NETWORK -> HugeIcons.Internet
    SettingsHubItemId.TERMUX -> HugeIcons.ComputerTerminal01
    SettingsHubItemId.SSH -> HugeIcons.Link01
    SettingsHubItemId.BROWSER -> HugeIcons.Earth
    SettingsHubItemId.SECRETS -> HugeIcons.Lock
    SettingsHubItemId.PERMISSIONS -> HugeIcons.Shield01
    SettingsHubItemId.ACCESSIBILITY -> HugeIcons.SmartPhone01
    SettingsHubItemId.SHIZUKU -> HugeIcons.Console
    SettingsHubItemId.NOTIF_ACCESS -> HugeIcons.Notification01
    SettingsHubItemId.FLOATING_BALL -> HugeIcons.Message01
    SettingsHubItemId.WORKFLOWS -> HugeIcons.Connect
    SettingsHubItemId.SCHEDULED -> HugeIcons.Clock02
    SettingsHubItemId.TELEGRAM -> HugeIcons.Telegram
    SettingsHubItemId.WEB -> HugeIcons.ServerStack01
    SettingsHubItemId.VISUAL_STYLE -> HugeIcons.Palette
    SettingsHubItemId.COLOR_MODE -> HugeIcons.Moon02
    SettingsHubItemId.FONTS -> HugeIcons.TextFont
    SettingsHubItemId.CHAT_LAYOUT -> HugeIcons.LayoutTop
    SettingsHubItemId.MESSAGE_DISPLAY -> HugeIcons.View
    SettingsHubItemId.INPUT -> HugeIcons.Keyboard
    SettingsHubItemId.QUICK_MESSAGES -> HugeIcons.Zap
    SettingsHubItemId.NOTIF_PREFS -> HugeIcons.Notification01
    SettingsHubItemId.BACKUP -> HugeIcons.Database02
    SettingsHubItemId.FILES -> HugeIcons.ImageUpload
    SettingsHubItemId.LOGS -> HugeIcons.Bookshelf01
    SettingsHubItemId.DOCTOR -> HugeIcons.Wrench01
    SettingsHubItemId.ABOUT -> HugeIcons.Clapping01
    SettingsHubItemId.SHARE -> HugeIcons.Share04
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Card(
        modifier = Modifier.padding(top = 12.dp),
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
