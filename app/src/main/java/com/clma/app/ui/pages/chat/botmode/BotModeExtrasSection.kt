package com.clma.app.ui.pages.chat.botmode

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.data.ai.tools.ApprovalMode
import com.clma.app.data.botmode.parseBotTeamPackMarkdown
import com.clma.app.data.botmode.toAssistant
import com.clma.app.data.botmode.withBotChannelRemoved
import com.clma.app.data.botmode.withBotChannelUpserted
import com.clma.app.data.datastore.BotChannel
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.preferences.ToolApprovalPreferences
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.context.LocalNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * Bot-mode settings extras: approval tier shortcut, lightweight channels, MCP connection shell,
 * and Markdown team-pack import. Only shown when Bot mode is on; classic mode stays untouched.
 */
@Composable
fun BotModeExtrasSection(
    settings: Settings,
    displaySetting: DisplaySetting,
    updateDisplaySetting: (DisplaySetting) -> Unit,
    updateSettings: (Settings) -> Unit,
) {
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val approvalPrefs: ToolApprovalPreferences = koinInject()
    val mode by approvalPrefs.modeFlow.collectAsStateWithLifecycle(initialValue = ApprovalMode.MANUAL)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.bot_mode_approval_tier_title)) },
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.bot_mode_approval_tier_title)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bot_mode_approval_tier_desc))
                    FlowRowChips(
                        selected = mode,
                        onSelect = { next -> scope.launch { approvalPrefs.setMode(next) } },
                    )
                    TextButton(onClick = { nav.navigate(Screen.SettingToolApprovals) }) {
                        Text(stringResource(R.string.bot_mode_approval_open_full))
                    }
                }
            },
        )
    }

    BotChannelsCard(
        settings = settings,
        displaySetting = displaySetting,
        updateDisplaySetting = updateDisplaySetting,
    )

    BotAppsConnectionsCard(
        settings = settings,
        onOpenMcp = { nav.navigate(Screen.SettingMcp) },
    )

    BotTeamPackImportCard(
        settings = settings,
        updateSettings = updateSettings,
        updateDisplaySetting = updateDisplaySetting,
        displaySetting = displaySetting,
    )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(
    selected: ApprovalMode,
    onSelect: (ApprovalMode) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == ApprovalMode.MANUAL,
            onClick = { onSelect(ApprovalMode.MANUAL) },
            label = { Text(stringResource(R.string.bot_mode_approval_manual)) },
        )
        FilterChip(
            selected = selected == ApprovalMode.SEMI,
            onClick = { onSelect(ApprovalMode.SEMI) },
            label = { Text(stringResource(R.string.bot_mode_approval_semi)) },
        )
        FilterChip(
            selected = selected == ApprovalMode.AUTO,
            onClick = { onSelect(ApprovalMode.AUTO) },
            label = { Text(stringResource(R.string.bot_mode_approval_auto)) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BotChannelsCard(
    settings: Settings,
    displaySetting: DisplaySetting,
    updateDisplaySetting: (DisplaySetting) -> Unit,
) {
    var draftName by remember { mutableStateOf("") }
    var draftDesc by remember { mutableStateOf("") }
    var draftMembers by remember { mutableStateOf<Set<Uuid>>(emptySet()) }

    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.bot_mode_channels_title)) },
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.bot_mode_channels_title)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bot_mode_channels_desc))
                    if (displaySetting.botChannels.isEmpty()) {
                        Text(
                            stringResource(R.string.bot_mode_channels_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        displaySetting.botChannels.forEach { ch ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(ch.name, style = MaterialTheme.typography.titleSmall)
                                if (ch.description.isNotBlank()) {
                                    Text(
                                        ch.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    stringResource(
                                        R.string.bot_mode_channels_members,
                                        ch.memberAssistantIds.size,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                TextButton(
                                    onClick = {
                                        updateDisplaySetting(
                                            displaySetting.withBotChannelRemoved(ch.id),
                                        )
                                    },
                                ) {
                                    Text(stringResource(R.string.bot_mode_channels_delete))
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text(stringResource(R.string.bot_mode_channels_name_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draftDesc,
                        onValueChange = { draftDesc = it },
                        label = { Text(stringResource(R.string.bot_mode_channels_desc_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Text(
                        stringResource(R.string.bot_mode_channels_pick_members),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        settings.assistants.forEach { bot ->
                            val selected = bot.id in draftMembers
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    draftMembers = if (selected) {
                                        draftMembers - bot.id
                                    } else {
                                        draftMembers + bot.id
                                    }
                                },
                                label = {
                                    Text(
                                        bot.name.ifBlank {
                                            stringResource(R.string.assistant_page_default_assistant)
                                        },
                                    )
                                },
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            val name = draftName.trim()
                            if (name.isEmpty()) return@TextButton
                            val channel = BotChannel(
                                name = name,
                                description = draftDesc.trim(),
                                memberAssistantIds = draftMembers.toList(),
                            )
                            updateDisplaySetting(displaySetting.withBotChannelUpserted(channel))
                            draftName = ""
                            draftDesc = ""
                            draftMembers = emptySet()
                        },
                        enabled = draftName.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.bot_mode_channels_save))
                    }
                }
            },
        )
    }
}

@Composable
private fun BotAppsConnectionsCard(
    settings: Settings,
    onOpenMcp: () -> Unit,
) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.bot_mode_apps_title)) },
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.bot_mode_apps_title)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.bot_mode_apps_desc))
                    if (settings.mcpServers.isEmpty()) {
                        Text(
                            stringResource(R.string.bot_mode_apps_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        settings.mcpServers.forEach { server ->
                            val name = server.commonOptions.name.ifBlank {
                                server.id.toString().take(8)
                            }
                            val status = if (server.commonOptions.enable) {
                                stringResource(R.string.bot_mode_apps_enabled)
                            } else {
                                stringResource(R.string.bot_mode_apps_disabled)
                            }
                            Text(
                                "$name · $status",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    TextButton(onClick = onOpenMcp) {
                        Text(stringResource(R.string.bot_mode_apps_open_settings))
                    }
                }
            },
        )
    }
}

@Composable
private fun BotTeamPackImportCard(
    settings: Settings,
    updateSettings: (Settings) -> Unit,
    updateDisplaySetting: (DisplaySetting) -> Unit,
    displaySetting: DisplaySetting,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    val preview = remember(previewText) {
        previewText?.let { parseBotTeamPackMarkdown(it) }
    }
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
            if (text.isNullOrBlank()) {
                previewError = context.getString(R.string.bot_mode_team_empty)
                previewText = null
            } else {
                previewError = null
                previewText = text
            }
        }
    }

    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.bot_mode_team_title)) },
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.bot_mode_team_title)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bot_mode_team_desc))
                    TextButton(onClick = { picker.launch(arrayOf("text/*", "text/markdown", "*/*")) }) {
                        Text(stringResource(R.string.bot_mode_team_pick))
                    }
                    previewError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    preview?.let { pack ->
                        if (pack.members.isEmpty()) {
                            Text(stringResource(R.string.bot_mode_team_empty))
                        } else {
                            Text(
                                stringResource(
                                    R.string.bot_mode_team_preview,
                                    pack.teamName.ifBlank { "Team" },
                                    pack.members.size,
                                ),
                            )
                            pack.members.forEach { m ->
                                Text(
                                    "· ${m.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(
                                onClick = {
                                    val created = pack.members.map { it.toAssistant() }
                                    updateSettings(
                                        settings.copy(assistants = settings.assistants + created),
                                    )
                                    if (pack.teamName.isNotBlank() && created.isNotEmpty()) {
                                        val channel = BotChannel(
                                            name = pack.teamName,
                                            description = pack.rawNotes,
                                            memberAssistantIds = created.map { it.id },
                                        )
                                        updateDisplaySetting(
                                            displaySetting.withBotChannelUpserted(channel),
                                        )
                                    }
                                    previewText = null
                                },
                            ) {
                                Text(stringResource(R.string.bot_mode_team_confirm))
                            }
                        }
                    }
                }
            },
        )
    }
}
