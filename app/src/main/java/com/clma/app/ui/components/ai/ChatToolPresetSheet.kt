package com.clma.app.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.ai.mcp.McpManager
import com.clma.app.data.ai.mcp.buildMcpToolName
import com.clma.app.data.ai.tools.ApprovalMode
import com.clma.app.data.ai.tools.ChatToolPresets
import com.clma.app.data.ai.tools.LocalToolOption
import com.clma.app.data.ai.tools.LocalTools
import com.clma.app.data.ai.tools.ToolApprovalAllowList
import com.clma.app.data.model.Assistant
import com.clma.app.data.preferences.ToolApprovalPreferences
import com.clma.app.ui.pages.setting.HighRiskTag
import com.clma.app.ui.pages.setting.MCP_WAIVER_TARGET
import com.clma.app.ui.pages.setting.localToolTitleRes
import com.clma.app.ui.pages.setting.waiverRiskText
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** Number of per-chat grants for [conversationId], re-read whenever the allow-list changes. */
@Composable
internal fun rememberChatGrantCount(conversationId: Uuid): Int {
    val version by ToolApprovalAllowList.version.collectAsStateWithLifecycle()
    return remember(conversationId, version) { ToolApprovalAllowList.listForChat(conversationId).size }
}

/** What the light confirmation is about to grant: one row, or the whole "allow all" batch. */
private sealed interface PendingPreset {
    data class One(val row: ChatToolPresets.Row) : PendingPreset
    data class All(val rows: List<ChatToolPresets.Row>) : PendingPreset
}

/**
 * The in-chat "本对话授权" sheet: pre-allow, for this conversation only, the tools that would
 * otherwise stop on an approval card — high-risk floor tools on the assistant's surface, relayed
 * MCP tools of its enabled servers, and blacklisted tools. Grants go to [ToolApprovalAllowList]
 * (in memory, keyed by [conversationId]) exactly like tapping 💬 on a card.
 *
 * Enabling a floor / MCP row asks for a light confirmation (risk line + one-line disclaimer, no
 * checkbox); blacklisted rows and revoking are instant. Raw tool ids are shown for now; Chinese
 * display names are a separate change.
 */
@Composable
fun ChatToolPresetSheet(
    conversationId: Uuid,
    assistant: Assistant,
    onDismiss: () -> Unit,
) {
    val localTools = koinInject<LocalTools>()
    val mcpManager = koinInject<McpManager>()
    val prefs = koinInject<ToolApprovalPreferences>()
    val blacklist by prefs.blacklistFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val mode by prefs.modeFlow.collectAsStateWithLifecycle(initialValue = ApprovalMode.MANUAL)
    val version by ToolApprovalAllowList.version.collectAsStateWithLifecycle()
    val granted = remember(conversationId, version) {
        ToolApprovalAllowList.listForChat(conversationId).toSet()
    }

    // The assistant's local surface, one group at a time so each name keeps its group title.
    // A factory failure degrades to "no local rows" rather than taking the sheet down.
    val groupOf: Map<String, LocalToolOption> = remember(assistant.id, assistant.localTools, assistant.disabledLocalTools) {
        runCatching {
            buildMap {
                assistant.localTools.forEach { group ->
                    localTools.getTools(listOf(group), disabledToolNames = assistant.disabledLocalTools)
                        .forEach { tool -> putIfAbsent(tool.name, group) }
                }
            }
        }.getOrDefault(emptyMap())
    }
    val mcpEntries = remember(assistant.id, assistant.mcpServers) {
        runCatching {
            mcpManager.getAllAvailableTools(assistant).map { (serverId, serverName, tool) ->
                ChatToolPresets.McpEntry(serverName, buildMcpToolName(serverId, serverName, tool.name))
            }
        }.getOrDefault(emptyList())
    }
    val rows = remember(groupOf, mcpEntries, blacklist) {
        ChatToolPresets.rows(groupOf.keys.toList(), mcpEntries, blacklist)
    }

    var pending by remember { mutableStateOf<PendingPreset?>(null) }
    pending?.let { p ->
        PresetConfirmDialog(
            pending = p,
            onConfirm = {
                pending = null
                when (p) {
                    is PendingPreset.One -> ToolApprovalAllowList.grantForChat(conversationId, p.row.toolName)
                    is PendingPreset.All ->
                        ToolApprovalAllowList.grantAllForChat(conversationId, p.rows.map { it.toolName })
                }
            },
            onDismiss = { pending = null },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.setting_page_tool_approvals_preset_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.setting_page_tool_approvals_preset_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val toGrant = ChatToolPresets.pendingForAllowAll(rows, granted)
                    Button(
                        enabled = toGrant.isNotEmpty(),
                        onClick = {
                            if (toGrant.any(ChatToolPresets::needsConfirm)) {
                                pending = PendingPreset.All(toGrant)
                            } else {
                                ToolApprovalAllowList.grantAllForChat(conversationId, toGrant.map { it.toolName })
                            }
                        },
                    ) {
                        Text(stringResource(R.string.setting_page_tool_approvals_preset_allow_all))
                    }
                }
            }

            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.setting_page_tool_approvals_preset_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            val floorRows = rows.filter { it.kind == ChatToolPresets.Kind.FLOOR }
            if (floorRows.isNotEmpty()) {
                item(key = "floor") {
                    PresetGroup(
                        title = stringResource(R.string.setting_page_tool_approvals_preset_group_floor),
                        rows = floorRows,
                        granted = granted,
                        groupOf = groupOf,
                        onToggle = { row, on -> applyToggle(conversationId, row, on) { pending = PendingPreset.One(it) } },
                    )
                }
            }
            rows.filter { it.kind == ChatToolPresets.Kind.MCP }
                .groupBy { it.serverName.orEmpty() }
                .forEach { (server, serverRows) ->
                    item(key = "mcp:$server") {
                        PresetGroup(
                            title = stringResource(R.string.setting_page_tool_approvals_preset_group_mcp, server),
                            rows = serverRows,
                            granted = granted,
                            groupOf = groupOf,
                            onToggle = { row, on -> applyToggle(conversationId, row, on) { pending = PendingPreset.One(it) } },
                        )
                    }
                }
            val blacklistRows = rows.filter { it.kind == ChatToolPresets.Kind.BLACKLIST }
            if (blacklistRows.isNotEmpty()) {
                item(key = "blacklist") {
                    PresetGroup(
                        title = stringResource(R.string.setting_page_tool_approvals_preset_group_blacklist),
                        rows = blacklistRows,
                        granted = granted,
                        groupOf = groupOf,
                        onToggle = { row, on -> applyToggle(conversationId, row, on) { pending = PendingPreset.One(it) } },
                    )
                }
            }

            item(key = "note") {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            when (mode) {
                                ApprovalMode.AUTO -> R.string.setting_page_tool_approvals_preset_note_auto
                                ApprovalMode.SEMI -> R.string.setting_page_tool_approvals_preset_note_semi
                                ApprovalMode.MANUAL -> R.string.setting_page_tool_approvals_preset_note_manual
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.setting_page_tool_approvals_preset_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Revoke instantly; grant instantly for blacklisted rows; otherwise ask [confirm] first. */
private fun applyToggle(
    conversationId: Uuid,
    row: ChatToolPresets.Row,
    on: Boolean,
    confirm: (ChatToolPresets.Row) -> Unit,
) {
    when {
        !on -> ToolApprovalAllowList.revokeForChat(conversationId, row.toolName)
        ChatToolPresets.needsConfirm(row) -> confirm(row)
        else -> ToolApprovalAllowList.grantForChat(conversationId, row.toolName)
    }
}

@Composable
private fun PresetGroup(
    title: String,
    rows: List<ChatToolPresets.Row>,
    granted: Set<String>,
    groupOf: Map<String, LocalToolOption>,
    onToggle: (ChatToolPresets.Row, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    R.string.setting_page_tool_approvals_preset_group_count,
                    rows.count { it.toolName in granted },
                    rows.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            rows.forEach { row ->
                val on = row.toolName in granted
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = row.toolName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (row.kind == ChatToolPresets.Kind.FLOOR) HighRiskTag()
                        }
                        val risky = ChatToolPresets.needsConfirm(row)
                        Text(
                            text = when {
                                on && risky -> stringResource(R.string.setting_page_tool_approvals_preset_row_allowed_risky)
                                on -> stringResource(R.string.setting_page_tool_approvals_preset_row_allowed)
                                else -> stringResource(R.string.setting_page_tool_approvals_preset_row_asks)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (on && risky) FontWeight.Medium else null,
                            color = if (on && risky) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        groupOf[row.toolName]?.let { group ->
                            Text(
                                text = stringResource(localToolTitleRes(group)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    Switch(checked = on, onCheckedChange = { onToggle(row, it) })
                }
            }
        }
    }
}

@Composable
private fun PresetConfirmDialog(
    pending: PendingPreset,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val disclaimer = stringResource(R.string.setting_page_tool_approvals_preset_confirm_disclaimer)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (pending is PendingPreset.All) R.string.setting_page_tool_approvals_preset_confirm_all_title
                    else R.string.setting_page_tool_approvals_preset_confirm_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (pending) {
                    is PendingPreset.One -> {
                        Text(
                            text = stringResource(R.string.setting_page_tool_approvals_preset_confirm_body, pending.row.toolName),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "• " + waiverRiskText(
                                if (pending.row.kind == ChatToolPresets.Kind.MCP) MCP_WAIVER_TARGET else pending.row.toolName
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is PendingPreset.All -> {
                        Text(
                            text = stringResource(
                                R.string.setting_page_tool_approvals_preset_confirm_all_body,
                                pending.rows.size,
                                pending.rows.count(ChatToolPresets::needsConfirm),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = pending.rows.filter(ChatToolPresets::needsConfirm)
                                .joinToString("\n") { "• " + it.toolName },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    text = disclaimer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                        .padding(10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.setting_page_tool_approvals_preset_confirm_ok),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_page_tool_approvals_preset_confirm_cancel))
            }
        },
    )
}
