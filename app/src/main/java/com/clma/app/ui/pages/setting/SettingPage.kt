package com.clma.app.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
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
import com.clma.app.data.secrets.AgentSecretsStore
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

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(section?.titleRes ?: R.string.settings)) },
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
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val context = LocalContext.current
        val statusSource = if (section != null) rememberHubStatusSource() else null
        val shareText = stringResource(R.string.setting_page_share_text)
        val shareLabel = stringResource(R.string.setting_page_share)
        val noShareApp = stringResource(R.string.setting_page_no_share_app)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (section == null) {
                item("search") {
                    HubSearchBox(onClick = { navController.navigate(Screen.SettingsSearch) })
                }
                if (settings.isNotConfigured()) {
                    item("provider_warning") { ProviderConfigWarningCard(navController) }
                }
                item("categories_title") {
                    Text(
                        text = stringResource(R.string.setting_hub_categories),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(settingsHubSections, key = { it.id.name }) { category ->
                    HubCategoryCard(
                        section = category,
                        onClick = { navController.navigate(category.route) },
                    )
                }
            } else {
                item("category_intro") {
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(section.descriptionRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.setting_hub_category_count, section.items.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                items(section.items, key = { it.name }) { itemId ->
                    val def = settingsHubItems.getValue(itemId)
                    HubRow(
                        icon = hubItemIcon(itemId),
                        title = stringResource(def.titleRes),
                        summary = statusSource?.let { hubItemSummary(itemId, settings, it) },
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
            item("bottom_spacer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HubCategoryCard(section: SettingsHubSectionDef, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (iconContainer, iconColor) = when (section.id) {
        SettingsHubSectionId.MODELS, SettingsHubSectionId.APPEARANCE ->
            colors.primaryContainer to colors.onPrimaryContainer
        SettingsHubSectionId.CHAT, SettingsHubSectionId.DATA ->
            colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    val icon = when (section.id) {
        SettingsHubSectionId.MODELS -> HugeIcons.ServerStack01
        SettingsHubSectionId.CHAT -> HugeIcons.Message01
        SettingsHubSectionId.CONNECTIONS -> HugeIcons.Puzzle
        SettingsHubSectionId.APPEARANCE -> HugeIcons.Palette
        SettingsHubSectionId.DATA -> HugeIcons.Shield01
        SettingsHubSectionId.ABOUT -> HugeIcons.Wrench01
        SettingsHubSectionId.AGENT -> HugeIcons.Robot01
        SettingsHubSectionId.DEVICE -> HugeIcons.SmartPhone01
        SettingsHubSectionId.AUTOMATION -> HugeIcons.Clock02
    }
    Surface(
        onClick = onClick,
        shape = HubCardShape,
        color = colors.surfaceContainerLowest,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 112.dp)
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = iconContainer,
                contentColor = iconColor,
            ) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(26.dp))
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(section.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(section.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Surface(shape = CircleShape, color = colors.surfaceContainerLow) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

private val HubCardShape = RoundedCornerShape(28.dp)
private val HubIconSize = 40.dp
private val HubRowStartPadding = 16.dp
private val HubRowGap = 14.dp

/** Entry into the existing settings search page. */
@Composable
private fun HubSearchBox(onClick: () -> Unit) {
    val hint = stringResource(R.string.setting_hub_search_hint)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.GlobalSearch,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
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

@Composable
private fun HubRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = HubCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 80.dp)
                .padding(start = HubRowStartPadding, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(HubIconSize)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(HubRowGap))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!summary.isNullOrBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    // Turn budget and tool steps are stored in TermuxPreferences; read them the same way the
    // 运行与上下文 page does.
    val termuxVm: SettingTermuxViewModel = koinViewModel()
    val termuxConfig by termuxVm.config.collectAsStateWithLifecycle()
    val approvalMode by approvalPrefs.modeFlow.collectAsStateWithLifecycle(initialValue = ApprovalMode.MANUAL)
    val storage by produceState(-1 to 0L) { value = filesManager.countChatFiles() }
    val colorMode by rememberColorMode()
    var notificationAccess by remember { mutableStateOf(false) }
    var secretCount by remember { mutableStateOf(0) }
    // Re-checked each time the hub comes back to the front (the user may have just granted it).
    LifecycleResumeEffect(context) {
        notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        // Names and timestamps only — entries() never decrypts a value, so this stays cheap.
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
        // No cheap, reliable status for these (they need a database read, a service probe or an
        // external app check), so the row shows only its chevron.
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
    SettingsHubItemId.PROVIDERS -> HugeIcons.ServerStack01
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
    SettingsHubItemId.CONNECTORS -> HugeIcons.Link01
    SettingsHubItemId.SECRETS -> HugeIcons.Lock
    SettingsHubItemId.PERMISSIONS -> HugeIcons.Shield01
    SettingsHubItemId.ACCESSIBILITY -> HugeIcons.SmartPhone01
    SettingsHubItemId.SHIZUKU -> HugeIcons.Console
    SettingsHubItemId.NOTIF_ACCESS -> HugeIcons.Notification01
    SettingsHubItemId.FLOATING_BALL -> HugeIcons.Message01
    SettingsHubItemId.WORKFLOWS -> HugeIcons.Connect
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
    SettingsHubItemId.ABOUT -> HugeIcons.Clapping01
    SettingsHubItemId.SHARE -> HugeIcons.Share04
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Surface(
        onClick = { navController.navigate(Screen.SettingProvider) },
        shape = HubCardShape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(HugeIcons.Alert01, null, Modifier.size(24.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
            Icon(HugeIcons.ArrowRight01, stringResource(R.string.setting_page_config), Modifier.size(20.dp))
        }
    }
}
