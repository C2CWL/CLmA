package com.clma.app.ui.pages.setting

import com.clma.app.data.botmode.classicAssistants
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiSparkles
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bell
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Browser
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Developer
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Flow
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.IdCard
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.LayoutTop
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Palette
import me.rerere.hugeicons.stroke.Plug
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Star
import me.rerere.hugeicons.stroke.TextFont
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
import com.clma.app.data.secrets.AgentSecretsStore
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.context.Navigator
import com.clma.app.ui.hooks.rememberColorMode
import com.clma.app.ui.pages.setting.termux.SettingTermuxViewModel
import com.clma.app.ui.theme.VisualStyle
import com.clma.app.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Home and category destinations share the same frame, but own separate navigation entries. */
@Composable
fun SettingPage(
    sectionId: SettingsHubSectionId? = null,
    vm: SettingVM = koinViewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val section = settingsHubSections.firstOrNull { it.id == sectionId }
    val pageColor = settingsPageContainerColor()
    val topBarColors = settingsTopBarColors()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(section?.titleRes ?: R.string.settings), color = settingsPrimaryTextColor()) },
                navigationIcon = { BackButton() },
                actions = {
                    if (section != null) {
                        IconButton(onClick = { navController.navigate(Screen.SettingsSearch) }) {
                            Icon(HugeIcons.GlobalSearch, stringResource(R.string.setting_hub_search_hint))
                        }
                    }
                    if (settings.developerMode) {
                        IconButton(onClick = { navController.navigate(Screen.Developer) }) {
                            Icon(HugeIcons.Developer, stringResource(R.string.accessibility_developer_options))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = pageColor,
    ) { innerPadding ->
        val context = LocalContext.current
        val statusSource = if (section != null) rememberHubStatusSource() else null
        val shareText = stringResource(R.string.setting_page_share_text)
        val shareLabel = stringResource(R.string.setting_page_share)
        val noShareApp = stringResource(R.string.setting_page_no_share_app)

        fun openItem(itemId: SettingsHubItemId) {
            when (itemId) {
                SettingsHubItemId.SHARE -> {
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.type = "text/plain"
                    intent.putExtra(Intent.EXTRA_TEXT, shareText)
                    try {
                        context.startActivity(Intent.createChooser(intent, shareLabel))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, noShareApp, Toast.LENGTH_SHORT).show()
                    }
                }
                else -> settingsHubItems.getValue(itemId).route?.let { navController.navigate(it) }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (section == null) 10.dp else 2.dp),
        ) {
            if (section == null) {
                item("search") {
                    HubSearchBox(onClick = { navController.navigate(Screen.SettingsSearch) })
                }
                if (settings.isNotConfigured()) {
                    item("provider_warning") {
                        ProviderConfigWarningCard(navController)
                    }
                }
                item("home_settings") {
                    HubLeafGroup(
                        itemIds = settingsHubHomeItems,
                        settings = settings,
                        status = statusSource,
                        onOpen = ::openItem,
                    )
                }
                item("categories_title") {
                    Text(
                        text = stringResource(R.string.setting_hub_categories),
                        style = MaterialTheme.typography.titleSmall,
                        color = settingsSecondaryTextColor(),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                items(settingsHubSections, key = { it.id.name }) { category ->
                    HubCategoryCard(
                        section = category,
                        onClick = { navController.navigate(category.route) },
                    )
                }
            } else {
                section.itemGroups().forEachIndexed { groupIndex, (subLabel, itemIds) ->
                    if (subLabel != null) {
                        item("sub_$groupIndex") {
                            Text(
                                text = stringResource(subLabel),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }
                    }
                    item("group_$groupIndex") {
                        HubLeafGroup(
                            itemIds = itemIds,
                            settings = settings,
                            status = statusSource,
                            onOpen = ::openItem,
                        )
                    }
                }
            }
            item("bottom_spacer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private val HubCategoryShape = RoundedCornerShape(22.dp)

/** Six category entry cards on the hub home — drill into the section's leaf list. */
@Composable
private fun HubCategoryCard(section: SettingsHubSectionDef, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (iconContainer, iconColor) = when (section.id) {
        SettingsHubSectionId.MODELS, SettingsHubSectionId.APPEARANCE ->
            colors.primaryContainer.copy(alpha = 0.72f) to colors.onPrimaryContainer
        SettingsHubSectionId.CHAT, SettingsHubSectionId.DATA ->
            colors.tertiaryContainer.copy(alpha = 0.72f) to colors.onTertiaryContainer
        SettingsHubSectionId.AGENT, SettingsHubSectionId.DEVICE ->
            colors.secondaryContainer.copy(alpha = 0.72f) to colors.onSecondaryContainer
    }
    val icon = when (section.id) {
        SettingsHubSectionId.MODELS -> HugeIcons.ServerStack01
        SettingsHubSectionId.CHAT -> HugeIcons.Message01
        SettingsHubSectionId.AGENT -> HugeIcons.Robot01
        SettingsHubSectionId.DEVICE -> HugeIcons.SmartPhone01
        SettingsHubSectionId.APPEARANCE -> HugeIcons.Palette
        SettingsHubSectionId.DATA -> HugeIcons.Database02
    }
    Surface(
        onClick = onClick,
        shape = HubCategoryShape,
        color = colors.surfaceContainerLowest,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 88.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = iconContainer,
                contentColor = iconColor,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(22.dp))
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(section.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = settingsPrimaryTextColor(),
                )
                Text(
                    text = stringResource(section.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryTextColor(),
                )
            }
            Surface(shape = CircleShape, color = colors.surfaceContainerLow.copy(alpha = 0.85f)) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        HugeIcons.ArrowRight01,
                        null,
                        Modifier.size(16.dp),
                        tint = settingsSecondaryTextColor(),
                    )
                }
            }
        }
    }
}

@Composable
private fun HubLeafGroup(
    itemIds: List<SettingsHubItemId>,
    settings: Settings,
    status: HubStatusSource?,
    onOpen: (SettingsHubItemId) -> Unit,
) {
    // Resolve composable summaries before entering CardGroup's non-@Composable DSL builder.
    val rows = itemIds.map { itemId ->
        val summary = if (itemId == SettingsHubItemId.BOT_MODE) {
            stringResource(
                if (settings.displaySetting.botModeEnabled) R.string.setting_hub_summary_on
                else R.string.setting_hub_summary_off,
            )
        } else {
            hubItemSummary(itemId, settings, requireNotNull(status))
        }
        Triple(itemId, settingsHubItems.getValue(itemId), summary)
    }
    CardGroup {
        rows.forEach { (itemId, def, summary) ->
            item(
                onClick = { onOpen(itemId) },
                leadingContent = {
                    Icon(
                        hubItemIcon(itemId),
                        contentDescription = null,
                        tint = settingsIconTint(),
                        modifier = Modifier.size(20.dp),
                    )
                },
                headlineContent = {
                    Text(
                        text = stringResource(def.titleRes),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.5.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = settingsPrimaryTextColor(),
                    )
                },
                supportingContent = if (!summary.isNullOrBlank()) {
                    {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = settingsSecondaryTextColor(),
                            maxLines = 1,
                        )
                    }
                } else {
                    null
                },
                trailingContent = {
                    Icon(
                        HugeIcons.ArrowRight01,
                        contentDescription = null,
                        tint = settingsSecondaryTextColor().copy(alpha = 0.55f),
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

private val HubSearchShape = RoundedCornerShape(13.dp)

/** Entry into the existing settings search page. */
@Composable
private fun HubSearchBox(onClick: () -> Unit) {
    val hint = stringResource(R.string.setting_hub_search_hint)
    Surface(
        shape = HubSearchShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp)
            .clip(HubSearchShape)
            .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp, max = 40.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.GlobalSearch,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
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
    val secretCount: Int,
)

@Composable
private fun rememberHubStatusSource(): HubStatusSource {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val approvalPrefs: ToolApprovalPreferences = koinInject()
    val secretsStore: AgentSecretsStore = koinInject()
    val termuxVm: SettingTermuxViewModel = koinViewModel()
    val termuxConfig by termuxVm.config.collectAsStateWithLifecycle()
    val approvalMode by approvalPrefs.modeFlow.collectAsStateWithLifecycle(initialValue = ApprovalMode.MANUAL)
    val storage by produceState(-1 to 0L) { value = filesManager.countChatFiles() }
    val colorMode by rememberColorMode()
    var notificationAccess by remember { mutableStateOf(false) }
    var secretCount by remember { mutableStateOf(0) }
    LifecycleResumeEffect(context) {
        notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        secretCount = secretsStore.entries().size
        onPauseOrDispose { }
    }
    return HubStatusSource(
        storage = storage,
        approvalMode = approvalMode,
        turnBudgetMinutes = termuxConfig.turnBudgetMs / 60_000L,
        maxToolSteps = termuxConfig.maxToolSteps,
        notificationAccess = notificationAccess,
        colorModeLabel = colorModeLabel(colorMode),
        secretCount = secretCount,
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
        SettingsHubItemId.BOT_MODE -> onOff(display.botModeEnabled)
        SettingsHubItemId.PROVIDERS -> {
            val enabled = settings.providers.count { it.enabled }
            if (enabled == 0) stringResource(R.string.setting_hub_summary_none)
            else stringResource(R.string.setting_hub_summary_enabled_count, enabled)
        }
        SettingsHubItemId.MODELS -> settings.getCurrentChatModel()?.let { it.displayName.ifBlank { it.modelId } }
            ?: stringResource(R.string.setting_hub_summary_not_selected)
        SettingsHubItemId.ASSISTANT -> count(settings.classicAssistants().size)
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
                ChatLayout.GEMINI -> R.string.setting_display_page_chat_layout_gemini
                ChatLayout.ORIGINAL -> R.string.setting_display_page_chat_layout_original
                ChatLayout.CLEAN -> R.string.setting_display_page_chat_layout_clean
                ChatLayout.MODERN -> R.string.setting_display_page_chat_layout_modern
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
        SettingsHubItemId.SECRETS -> count(status.secretCount)
        SettingsHubItemId.ABOUT -> "v${BuildConfig.VERSION_NAME}"
        SettingsHubItemId.WORKSPACES,
        SettingsHubItemId.SKILLS,
        SettingsHubItemId.TERMUX,
        SettingsHubItemId.SSH,
        SettingsHubItemId.BROWSER,
        SettingsHubItemId.CONNECTORS,
        SettingsHubItemId.PERMISSIONS,
        SettingsHubItemId.ACCESSIBILITY,
        SettingsHubItemId.SHIZUKU,
        SettingsHubItemId.WORKFLOWS,
        SettingsHubItemId.SCHEDULED,
        SettingsHubItemId.MESSAGE_DISPLAY,
        SettingsHubItemId.DOCTOR,
        SettingsHubItemId.SHARE -> null
    }
}

private fun hubItemIcon(id: SettingsHubItemId): ImageVector = when (id) {
    SettingsHubItemId.BOT_MODE -> HugeIcons.Robot01
    SettingsHubItemId.PROVIDERS -> HugeIcons.IdCard
    SettingsHubItemId.MODELS -> HugeIcons.Star
    SettingsHubItemId.ASSISTANT -> HugeIcons.Robot01
    SettingsHubItemId.PROMPTS -> HugeIcons.Book03
    SettingsHubItemId.SEARCH -> HugeIcons.Earth
    SettingsHubItemId.SPEECH -> HugeIcons.Mic01
    SettingsHubItemId.WORKSPACES -> HugeIcons.Folder01
    SettingsHubItemId.SKILLS -> HugeIcons.AiSparkles
    SettingsHubItemId.MCP -> HugeIcons.Plug
    SettingsHubItemId.SUB_AGENTS -> HugeIcons.LookTop
    SettingsHubItemId.TOOL_APPROVALS -> HugeIcons.Shield01
    SettingsHubItemId.RUNTIME -> HugeIcons.Timer01
    SettingsHubItemId.NETWORK -> HugeIcons.Internet
    SettingsHubItemId.TERMUX -> HugeIcons.ComputerTerminal01
    SettingsHubItemId.SSH -> HugeIcons.Link01
    SettingsHubItemId.BROWSER -> HugeIcons.Browser
    SettingsHubItemId.CONNECTORS -> HugeIcons.Link01
    SettingsHubItemId.SECRETS -> HugeIcons.Lock
    SettingsHubItemId.PERMISSIONS -> HugeIcons.Shield01
    SettingsHubItemId.ACCESSIBILITY -> HugeIcons.SmartPhone01
    SettingsHubItemId.SHIZUKU -> HugeIcons.Zap
    SettingsHubItemId.NOTIF_ACCESS -> HugeIcons.Bell
    SettingsHubItemId.FLOATING_BALL -> HugeIcons.Message01
    SettingsHubItemId.WORKFLOWS -> HugeIcons.Flow
    SettingsHubItemId.SCHEDULED -> HugeIcons.Clock02
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
    SettingsHubItemId.ABOUT -> HugeIcons.InformationCircle
    SettingsHubItemId.SHARE -> HugeIcons.Share04
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Surface(
        onClick = { navController.navigate(Screen.SettingProvider) },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HugeIcons.Alert01, null, Modifier.size(22.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.setting_page_config_api_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.setting_page_config_api_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(HugeIcons.ArrowRight01, stringResource(R.string.setting_page_config), Modifier.size(18.dp))
        }
    }
}
