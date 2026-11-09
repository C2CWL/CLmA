package com.clma.app.ui.pages.bot

import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.data.ai.tools.ApprovalMode
import com.clma.app.data.ai.tools.local.VirtualDisplayController
import com.clma.app.data.botmode.BotCostPolicy
import com.clma.app.data.botmode.isMainBot
import com.clma.app.data.datastore.findModelById
import com.clma.app.data.model.isBot
import com.clma.app.data.preferences.ToolApprovalPreferences
import com.clma.app.ui.components.ui.AssistantAvatar
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.pages.assistant.AssistantVM
import com.clma.app.ui.pages.chat.botmode.BotModelPickerButton
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * A bot's info page in Bot mode (the approved 03-bot-info mockup): header with pin / hide /
 * main, then folding groups for conversations, model & prompt, cost saving, approvals, app
 * connections, virtual displays and more. Everything edits the real bot; nothing here opens the
 * classic assistant list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotInfoPage(botId: String, conversationId: String?) {
    val vm: BotInfoVM = koinViewModel(key = "bot-info-$botId", parameters = { parametersOf(botId) })
    val assistantVm: AssistantVM = koinViewModel()
    val nav = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val memoryCount by vm.memoryCount.collectAsStateWithLifecycle()
    val approvalPrefs: ToolApprovalPreferences = koinInject()
    val approvalMode by approvalPrefs.modeFlow.collectAsStateWithLifecycle(initialValue = ApprovalMode.MANUAL)
    val displays by VirtualDisplayController.activeDisplays.collectAsStateWithLifecycle()

    val bot = settings.assistants.find { it.id == vm.id && it.isBot }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var vdBusy by remember { mutableStateOf(false) }
    var vdError by remember { mutableStateOf<String?>(null) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    var showAllConversations by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri: Uri? ->
        val target = bot ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val text = vm.teamPackMarkdown(target)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
                }.getOrDefault(false)
            }
            exportNote = context.getString(if (ok) R.string.bot_info_export_done else R.string.bot_info_export_failed)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bot_info_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (bot == null) {
            Text(stringResource(R.string.bot_info_gone), modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val isMain = settings.isMainBot(bot.id)
        val pinned = vm.isPinned(settings)
        val hidden = vm.isHidden(settings)
        val name = bot.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
        val openConversation: (Uuid) -> Unit = { id ->
            vm.openConversation(id) { conv -> nav.clearAndNavigate(Screen.BotChat(bot.id.toString(), conv.toString())) }
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssistantAvatar(
                    assistant = bot,
                    name = name,
                    modifier = Modifier.size(88.dp),
                    onUpdate = { vm.setAvatar(it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { renaming = true },
                    )
                    if (isMain) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                stringResource(R.string.bot_home_main_badge),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (bot.systemPrompt.isNotBlank()) {
                    Text(
                        bot.systemPrompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.bot_info_pin))
                            Spacer(Modifier.width(6.dp))
                            Switch(checked = pinned, onCheckedChange = { vm.setPinned(it) })
                        }
                    }
                    OutlinedButton(onClick = { vm.setHidden(!hidden) }, enabled = !isMain || hidden) {
                        Text(stringResource(if (hidden) R.string.bot_info_unhide else R.string.bot_info_hide))
                    }
                }
                if (!isMain) {
                    TextButton(onClick = { vm.setMain() }) { Text(stringResource(R.string.bot_home_menu_set_main)) }
                } else {
                    Text(
                        stringResource(R.string.bot_info_main_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }

            // Conversations
            BotCollapsibleGroup(
                key = "conversations",
                title = stringResource(R.string.bot_info_group_conversations),
                summary = stringResource(R.string.bot_info_conversations_count, conversations.size),
            ) {
                FilledTonalButton(onClick = { openConversation(Uuid.random()) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bot_info_new_topic))
                }
                if (conversations.isEmpty()) {
                    Text(
                        stringResource(R.string.bot_info_no_conversations),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val shown = if (showAllConversations) conversations else conversations.take(5)
                    shown.forEach { conv ->
                        val current = conv.id.toString() == conversationId
                        InfoRow(
                            title = conv.title.ifBlank { stringResource(R.string.bot_info_untitled_conversation) },
                            value = if (current) stringResource(R.string.bot_info_current_conversation) else botTimeText(conv.updateAt),
                            onClick = { openConversation(conv.id) },
                        )
                    }
                    if (conversations.size > 5) {
                        TextButton(onClick = { showAllConversations = !showAllConversations }) {
                            Text(
                                stringResource(
                                    if (showAllConversations) R.string.bot_info_show_less else R.string.bot_info_show_all,
                                    conversations.size,
                                ),
                            )
                        }
                    }
                }
            }

            // Model, prompt, memory
            val modelLabel = settings.findModelById(bot.chatModelId)?.displayName
            BotCollapsibleGroup(
                key = "model",
                title = stringResource(R.string.bot_info_group_model),
                summary = modelLabel ?: stringResource(R.string.bot_info_model_default),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bot_info_chat_model), modifier = Modifier.weight(1f))
                    BotModelPickerButton(
                        modelId = bot.chatModelId,
                        modelLabel = modelLabel ?: stringResource(R.string.bot_info_model_default),
                        providers = settings.providers,
                        onSelect = { vm.setModel(it.id) },
                    )
                }
                InfoRow(
                    title = stringResource(R.string.bot_info_system_prompt),
                    value = if (bot.systemPrompt.isBlank()) stringResource(R.string.bot_info_not_set)
                    else stringResource(R.string.bot_info_chars, bot.systemPrompt.length),
                    onClick = { nav.navigate(Screen.AssistantPrompt(bot.id.toString())) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.bot_info_memory))
                        Text(
                            stringResource(R.string.bot_info_memory_count, memoryCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = bot.enableMemory, onCheckedChange = { vm.setMemoryEnabled(it) })
                }
                InfoRow(
                    title = stringResource(R.string.bot_info_memory_manage),
                    value = "",
                    onClick = { nav.navigate(Screen.AssistantMemory(bot.id.toString())) },
                )
            }

            // Cost saving (#261)
            BotCollapsibleGroup(
                key = "cost",
                title = stringResource(R.string.bot_info_group_cost),
                summary = stringResource(if (BotCostPolicy.isActive(bot)) R.string.bot_info_on else R.string.bot_info_off),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.assistant_page_bot_cost_saving))
                        Text(
                            stringResource(R.string.assistant_page_bot_cost_saving_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = BotCostPolicy.isActive(bot), onCheckedChange = { vm.setCostSaving(it) })
                }
            }

            // Approval tier (global)
            BotCollapsibleGroup(
                key = "approval",
                title = stringResource(R.string.bot_mode_approval_tier_title),
                summary = approvalLabel(approvalMode),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = listOf(ApprovalMode.MANUAL, ApprovalMode.SEMI, ApprovalMode.AUTO)
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = approvalMode == mode,
                            onClick = { scope.launch { approvalPrefs.setMode(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(approvalLabel(mode)) }
                    }
                }
                Text(
                    stringResource(R.string.bot_info_approval_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfoRow(
                    title = stringResource(R.string.bot_mode_approval_open_full),
                    value = "",
                    onClick = { nav.navigate(Screen.SettingToolApprovals) },
                )
            }

            // App connections
            BotCollapsibleGroup(
                key = "apps",
                title = stringResource(R.string.bot_mode_apps_title),
                summary = stringResource(R.string.bot_info_apps_count, bot.mcpServers.count { id -> settings.mcpServers.any { it.id == id } }),
            ) {
                if (settings.mcpServers.isEmpty()) {
                    Text(
                        stringResource(R.string.bot_mode_apps_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    settings.mcpServers.forEach { server ->
                        val serverName = server.commonOptions.name.ifBlank { server.id.toString().take(8) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(serverName)
                                Text(
                                    stringResource(
                                        if (server.commonOptions.enable) R.string.bot_mode_apps_enabled else R.string.bot_mode_apps_disabled,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = server.id in bot.mcpServers,
                                onCheckedChange = { vm.toggleMcpServer(server.id) },
                            )
                        }
                    }
                }
                InfoRow(title = stringResource(R.string.bot_mode_apps_open_settings), value = "", onClick = { nav.navigate(Screen.SettingMcp) })
                InfoRow(title = stringResource(R.string.bot_info_connectors), value = "", onClick = { nav.navigate(Screen.SettingConnectors) })
            }

            // Virtual displays
            BotCollapsibleGroup(
                key = "displays",
                title = stringResource(R.string.bot_mode_task_vd_section),
                summary = if (displays.isEmpty()) stringResource(R.string.bot_mode_task_vd_empty)
                else stringResource(R.string.bot_mode_task_vd_active, displays.size),
                initiallyExpanded = false,
            ) {
                if (displays.isEmpty()) {
                    Text(
                        stringResource(R.string.bot_mode_task_vd_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        enabled = !vdBusy,
                        onClick = {
                            vdBusy = true
                            vdError = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    VirtualDisplayController.create(
                                        context.applicationContext,
                                        width = 720,
                                        height = 1280,
                                        densityDpi = 320,
                                        showPreview = true,
                                    )
                                }
                                vdBusy = false
                                when (result) {
                                    is VirtualDisplayController.Result.Ok ->
                                        if (!AndroidSettings.canDrawOverlays(context)) {
                                            vdError = context.getString(R.string.bot_mode_task_vd_overlay_required)
                                        }
                                    is VirtualDisplayController.Result.Error -> vdError = result.message
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.bot_mode_task_vd_create)) }
                } else {
                    displays.forEach { display ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.bot_mode_task_vd_item, display.displayId, display.width, display.height),
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(onClick = {
                                if (!AndroidSettings.canDrawOverlays(context)) {
                                    vdError = context.getString(R.string.bot_mode_task_vd_overlay_required)
                                } else {
                                    vdError = null
                                    VirtualDisplayController.showPreview(context, display.displayId)
                                }
                            }) { Text(stringResource(R.string.bot_mode_task_vd_preview)) }
                        }
                    }
                }
                vdError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                InfoRow(title = stringResource(R.string.bot_mode_task_vd_manage), value = "", onClick = { nav.navigate(Screen.VirtualDisplays) })
            }

            // More
            BotCollapsibleGroup(
                key = "more",
                title = stringResource(R.string.bot_info_group_more),
                initiallyExpanded = false,
            ) {
                InfoRow(
                    title = stringResource(R.string.bot_info_export_team_pack),
                    value = "",
                    onClick = { exportLauncher.launch("${name.take(40)}.md") },
                )
                InfoRow(
                    title = stringResource(R.string.bot_info_import_team_pack),
                    value = "",
                    onClick = { nav.navigate(Screen.SettingChatLayout) },
                )
                exportNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                InfoRow(
                    title = stringResource(R.string.bot_info_advanced),
                    value = "",
                    onClick = { nav.navigate(Screen.AssistantDetail(bot.id.toString())) },
                )
                if (isMain) {
                    Text(
                        stringResource(R.string.bot_info_delete_main_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.bot_home_menu_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (renaming) {
            var draft by rememberSaveable { mutableStateOf(bot.name) }
            AlertDialog(
                onDismissRequest = { renaming = false },
                title = { Text(stringResource(R.string.bot_info_rename)) },
                text = {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true)
                },
                confirmButton = {
                    TextButton(enabled = draft.isNotBlank(), onClick = {
                        renaming = false
                        vm.rename(draft)
                    }) { Text(stringResource(R.string.bot_mode_channels_save)) }
                },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.bot_home_cancel)) } },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.bot_home_delete_title)) },
                text = { Text(stringResource(R.string.bot_home_delete_confirm, name)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        assistantVm.removeAssistant(bot)
                        nav.clearAndNavigate(Screen.BotHome)
                    }) { Text(stringResource(R.string.bot_home_menu_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.bot_home_cancel)) } },
            )
        }
    }
}

@Composable
private fun approvalLabel(mode: ApprovalMode): String = stringResource(
    when (mode) {
        ApprovalMode.MANUAL -> R.string.bot_mode_approval_manual
        ApprovalMode.SEMI -> R.string.bot_mode_approval_semi
        ApprovalMode.AUTO -> R.string.bot_mode_approval_auto
    },
)

@Composable
private fun InfoRow(title: String, value: String, onClick: () -> Unit, icon: ImageVector = Lucide.ChevronRight) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value.isNotEmpty()) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
