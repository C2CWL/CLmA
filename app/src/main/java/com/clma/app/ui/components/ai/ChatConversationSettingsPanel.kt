package com.clma.app.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.ai.provider.Model
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.data.ai.mcp.McpManager
import com.clma.app.data.ai.tools.ApprovalMode
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.datastore.getCurrentAssistant
import com.clma.app.data.datastore.getContextCompactionTargetTokens
import com.clma.app.data.datastore.getEffectiveContextLength
import com.clma.app.data.datastore.resolveChatModel
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Conversation
import com.clma.app.data.preferences.ToolApprovalPreferences
import com.clma.app.data.repository.WorkspaceRepository
import com.clma.app.subagent.SubAgentDispatchProfile
import com.clma.app.ui.components.ui.AppDropdownMenu
import com.clma.app.ui.components.ui.AppDropdownMenuItem
import com.clma.app.ui.context.LocalNavController
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Package
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Tick02
import org.koin.compose.koinInject

/**
 * Top-anchored 「本对话设置」 panel: conversation outline/compress, search + workspace,
 * and Agent controls (approval, dispatch, chat grants, MCP, extensions).
 */
@Composable
fun ChatConversationSettingsPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    enableSearch: Boolean,
    model: Model?,
    mcpManager: McpManager,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Deferred<Result<Unit>>,
    onOpenMessageOutline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val approvalPrefs = koinInject<ToolApprovalPreferences>()
    val settingsStore = koinInject<SettingsStore>()
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
    val approvalMode by approvalPrefs.modeFlow.collectAsStateWithLifecycle(initialValue = ApprovalMode.MANUAL)
    val liveSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val dispatchProfile = liveSettings.subAgentDispatchProfile
    val currentModel = model ?: settings.resolveChatModel(conversation.chatModelId, conversation.assistantId)

    var showPresetSheet by remember { mutableStateOf(false) }
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var pendingAuto by remember { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onDismiss),
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .heightIn(max = 520.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_conversation_settings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                AgentModeHint(agentModeEnabled = conversation.agentModeEnabled)

                SettingsGroupCard(title = stringResource(R.string.chat_conversation_settings_group_chat)) {
                    SettingsActionRow(
                        icon = Lucide.List,
                        title = stringResource(R.string.chat_conversation_settings_message_outline),
                        subtitle = stringResource(R.string.chat_conversation_settings_message_outline_desc),
                        onClick = {
                            onDismiss()
                            onOpenMessageOutline()
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 50.dp, end = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    ListItem(
                        leadingContent = {
                            SettingsIconBadge(icon = { Icon(HugeIcons.Package01, null, Modifier.size(16.dp)) })
                        },
                        headlineContent = {
                            Text(stringResource(R.string.chat_page_compress_context))
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.chat_page_compress_context_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            if (conversation.messageNodes.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.chat_page_message_count,
                                        conversation.messageNodes.size,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Icon(
                                    HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { showCompressDialog = true },
                    )
                }

                SettingsGroupCard(title = stringResource(R.string.chat_conversation_settings_group_general)) {
                    SearchControlCard(
                        enableSearch = enableSearch,
                        settings = settings,
                        model = currentModel,
                        onUpdateSearchMode = onUpdateSearchMode,
                        onUpdateSearchService = onUpdateSearchService,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    if (workspaces.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 50.dp, end = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        WorkspacePickerListItem(
                            assistant = assistant,
                            conversation = conversation,
                            workspaces = workspaces,
                            onUpdateAssistant = onUpdateAssistant,
                            onUpdateConversation = onUpdateConversation,
                            onNavigateToDetail = { id ->
                                onDismiss()
                                navController.navigate(Screen.WorkspaceDetail(id))
                            },
                            onNavigateToTerminal = { id ->
                                onDismiss()
                                navController.navigate(Screen.WorkspaceTerminal(id))
                            },
                            onNavigateToManage = {
                                onDismiss()
                                navController.navigate(Screen.Workspaces)
                            },
                            containerColor = Color.Transparent,
                        )
                    }
                }

                SettingsGroupCard(title = stringResource(R.string.chat_conversation_settings_group_agent)) {
                    ApprovalModeRow(
                        mode = approvalMode,
                        onSelect = { mode ->
                            if (mode == ApprovalMode.AUTO && approvalMode != ApprovalMode.AUTO) {
                                pendingAuto = true
                            } else {
                                scope.launch { approvalPrefs.setMode(mode) }
                            }
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 50.dp, end = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    DispatchProfileRow(
                        profile = dispatchProfile,
                        onSelect = { profile ->
                            scope.launch {
                                settingsStore.update { it.copy(subAgentDispatchProfile = profile) }
                            }
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 50.dp, end = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    val grantCount = rememberChatGrantCount(conversation.id)
                    ListItem(
                        leadingContent = {
                            SettingsIconBadge(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ) {
                                Text("\uD83D\uDD13", style = MaterialTheme.typography.labelMedium)
                            }
                        },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.setting_page_tool_approvals_preset_chip))
                                if (grantCount > 0) {
                                    Spacer(Modifier.size(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Text(
                                            text = grantCount.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_tool_approvals_preset_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Icon(
                                HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { showPresetSheet = true },
                    )

                    if (settings.mcpServers.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 50.dp, end = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        McpPickerListItem(
                            assistant = assistant,
                            servers = settings.mcpServers,
                            mcpManager = mcpManager,
                            onUpdateAssistant = onUpdateAssistant,
                            modifier = Modifier,
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 50.dp, end = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    val modeAndLorebookCount =
                        if (assistant.allowConversationPromptInjection) {
                            conversation.modeInjectionIds.size + conversation.lorebookIds.size
                        } else {
                            assistant.modeInjectionIds.size + assistant.lorebookIds.size
                        }
                    val activeCount =
                        assistant.quickMessageIds.size +
                            modeAndLorebookCount +
                            assistant.enabledSkills.size
                    ListItem(
                        leadingContent = {
                            SettingsIconBadge {
                                Icon(Lucide.Package, null, Modifier.size(16.dp))
                            }
                        },
                        headlineContent = {
                            Text(stringResource(R.string.assistant_page_tab_extensions))
                        },
                        trailingContent = {
                            if (activeCount > 0) {
                                Text(
                                    text = activeCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { showInjectionSheet = true },
                    )
                }
            }
        }
    }

    if (showPresetSheet) {
        ChatToolPresetSheet(
            conversationId = conversation.id,
            assistant = liveSettings.getCurrentAssistant(),
            onDismiss = { showPresetSheet = false },
        )
    }
    if (showInjectionSheet) {
        InjectionQuickConfigSheet(
            conversation = conversation,
            assistant = assistant,
            settings = settings,
            onUpdateAssistant = onUpdateAssistant,
            onUpdateConversation = onUpdateConversation,
            onDismiss = { showInjectionSheet = false },
            onDismissAll = {
                showInjectionSheet = false
                onDismiss()
            },
        )
    }
    if (showCompressDialog) {
        CompressContextDialog(
            defaultTargetTokens = settings.getContextCompactionTargetTokens(
                settings.getEffectiveContextLength(currentModel),
            ),
            onDismiss = { showCompressDialog = false },
            onConfirm = { additionalPrompt, targetTokens, keepRecentMessages ->
                onCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
        )
    }
    if (pendingAuto) {
        AlertDialog(
            onDismissRequest = { pendingAuto = false },
            title = {
                Text(
                    text = stringResource(R.string.setting_page_tool_approvals_auto_dialog_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = { Text(stringResource(R.string.setting_page_tool_approvals_auto_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingAuto = false
                    scope.launch { approvalPrefs.setMode(ApprovalMode.AUTO) }
                }) {
                    Text(
                        text = stringResource(R.string.setting_page_tool_approvals_auto_dialog_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAuto = false }) {
                    Text(stringResource(R.string.setting_page_tool_approvals_auto_dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun AgentModeHint(agentModeEnabled: Boolean) {
    val tint = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = Lucide.Bot,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(13.dp),
                tint = tint.copy(alpha = 0.85f),
            )
            Text(
                text = if (agentModeEnabled) {
                    stringResource(R.string.chat_conversation_settings_hint_agent)
                } else {
                    stringResource(R.string.chat_conversation_settings_hint_chat)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsIconBadge(
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
    contentColor: Color = MaterialTheme.colorScheme.primary,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
        ) { icon() }
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            SettingsIconBadge {
                Icon(icon, null, Modifier.size(16.dp))
            }
        },
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Icon(
                HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ApprovalModeRow(
    mode: ApprovalMode,
    onSelect: (ApprovalMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            leadingContent = {
                SettingsIconBadge {
                    Icon(HugeIcons.Tick02, null, Modifier.size(16.dp))
                }
            },
            headlineContent = {
                Text(stringResource(R.string.setting_page_tool_approvals_mode_title))
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.chat_conversation_settings_approval_modes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    onClick = { open = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(approvalModeLabel(mode), style = MaterialTheme.typography.labelMedium)
                        Icon(HugeIcons.ArrowDown01, null, Modifier.size(12.dp))
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { open = true },
        )
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ApprovalMode.entries.forEach { entry ->
                AppDropdownMenuItem(
                    text = { Text(approvalModeLabel(entry)) },
                    leadingIcon = {
                        if (entry == mode) {
                            Icon(HugeIcons.Tick02, null, Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(entry)
                    },
                )
            }
        }
    }
}

@Composable
private fun DispatchProfileRow(
    profile: SubAgentDispatchProfile,
    onSelect: (SubAgentDispatchProfile) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            leadingContent = {
                SettingsIconBadge {
                    Icon(Lucide.Bot, null, Modifier.size(16.dp))
                }
            },
            headlineContent = {
                Text(stringResource(R.string.setting_sub_agents_dispatch_profile_title))
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.chat_conversation_settings_dispatch_range),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    onClick = { open = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(dispatchProfileLabel(profile), style = MaterialTheme.typography.labelMedium)
                        Icon(HugeIcons.ArrowDown01, null, Modifier.size(12.dp))
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { open = true },
        )
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SubAgentDispatchProfile.entries.forEach { entry ->
                AppDropdownMenuItem(
                    text = { Text(dispatchProfileLabel(entry)) },
                    leadingIcon = {
                        if (entry == profile) {
                            Icon(HugeIcons.Tick02, null, Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(entry)
                    },
                )
            }
        }
    }
}

@Composable
private fun approvalModeLabel(mode: ApprovalMode): String = stringResource(
    when (mode) {
        ApprovalMode.AUTO -> R.string.setting_page_tool_approvals_mode_auto
        ApprovalMode.SEMI -> R.string.setting_page_tool_approvals_mode_semi
        ApprovalMode.MANUAL -> R.string.setting_page_tool_approvals_mode_manual
    }
)

@Composable
private fun dispatchProfileLabel(profile: SubAgentDispatchProfile): String = stringResource(
    when (profile) {
        SubAgentDispatchProfile.NEVER -> R.string.setting_sub_agents_profile_never
        SubAgentDispatchProfile.ASSIST -> R.string.setting_sub_agents_profile_assist
        SubAgentDispatchProfile.NORMAL -> R.string.setting_sub_agents_profile_normal
        SubAgentDispatchProfile.BURST -> R.string.setting_sub_agents_profile_burst
        SubAgentDispatchProfile.UNLEASHED -> R.string.setting_sub_agents_profile_unleashed
    }
)
