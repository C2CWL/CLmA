package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agentdef.LocalToolGroups
import me.rerere.rikkahub.data.ai.tools.LocalToolInventory
import me.rerere.rikkahub.data.ai.tools.LocalToolPalette
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.ai.tools.ApprovalMode
import me.rerere.rikkahub.data.ai.tools.ToolApprovalPolicy
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * Tool authorization, blacklist era.
 *
 * Every tool runs without asking by default; this screen is where the user says which ones must
 * ask. The list is built from the live local-tool factory (one [LocalToolPalette] group at a
 * time), so the names shown can never drift from the surface the model actually receives, and a
 * switch is simply "is this tool on the blacklist".
 *
 * Two things cannot be switched off: the non-waivable floor
 * ([ToolApprovalDefaults.NO_ALWAYS_ALLOW] — privilege-escalation surfaces) and every opaque
 * `mcp__*` relayed tool. Their rows render an on, disabled switch. The three-mode selector at
 * the top picks the posture: Auto (everything but the floor auto-approves), Semi (blacklist
 * decides), Manual (every call asks).
 */
@Composable
fun SettingToolApprovalsPage() {
    val prefs: ToolApprovalPreferences = koinInject()
    val blacklist by prefs.blacklistFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val mode by prefs.modeFlow.collectAsStateWithLifecycle(initialValue = ApprovalMode.MANUAL)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var pendingAuto by remember { mutableStateOf(false) }
    if (pendingAuto) {
        AlertDialog(
            onDismissRequest = { pendingAuto = false },
            title = {
                Text(
                    text = stringResource(R.string.setting_page_tool_approvals_auto_dialog_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(stringResource(R.string.setting_page_tool_approvals_auto_dialog_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingAuto = false
                    scope.launch { prefs.setMode(ApprovalMode.AUTO) }
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

    // The local tool directory, one group at a time - the same construction the expert editor's
    // palette uses, so a name here and a name there can never disagree. A failure hides the list
    // rather than taking the screen down with it.
    val localToolFactory = koinInject<LocalTools>()
    val toolPalette = remember {
        runCatching {
            LocalToolPalette.build(
                LocalToolGroups.all.map { group ->
                    LocalToolInventory(
                        group = group,
                        tools = localToolFactory.getTools(listOf(group)),
                    )
                }
            )
        }.getOrNull()
    }

    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tool_approvals)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Three-mode selector: Auto / Semi / Manual. Replaces the old YOLO toggle.
            // Auto still asks for the floor (mcp_add, eval_js, …); Manual asks for everything.
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (mode == ApprovalMode.AUTO) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_tool_approvals_mode_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (mode == ApprovalMode.AUTO) MaterialTheme.colorScheme.onErrorContainer
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = mode == ApprovalMode.AUTO,
                            onClick = { if (mode != ApprovalMode.AUTO) pendingAuto = true },
                            label = { Text(stringResource(R.string.setting_page_tool_approvals_mode_auto)) },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = mode == ApprovalMode.SEMI,
                            onClick = { scope.launch { prefs.setMode(ApprovalMode.SEMI) } },
                            label = { Text(stringResource(R.string.setting_page_tool_approvals_mode_semi)) },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = mode == ApprovalMode.MANUAL,
                            onClick = { scope.launch { prefs.setMode(ApprovalMode.MANUAL) } },
                            label = { Text(stringResource(R.string.setting_page_tool_approvals_mode_manual)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = when (mode) {
                            ApprovalMode.AUTO -> stringResource(R.string.setting_page_tool_approvals_auto_desc)
                            ApprovalMode.SEMI -> stringResource(R.string.setting_page_tool_approvals_semi_desc)
                            ApprovalMode.MANUAL -> stringResource(R.string.setting_page_tool_approvals_manual_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mode == ApprovalMode.AUTO) MaterialTheme.colorScheme.onErrorContainer
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = when (mode) {
                            ApprovalMode.AUTO -> stringResource(R.string.setting_page_tool_approvals_blacklist_ignored)
                            ApprovalMode.SEMI -> stringResource(R.string.setting_page_tool_approvals_header)
                            ApprovalMode.MANUAL -> stringResource(R.string.setting_page_tool_approvals_blacklist_manual)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mode == ApprovalMode.SEMI) {
                        Text(
                            text = if (blacklist.isEmpty()) {
                                stringResource(R.string.setting_page_tool_approvals_empty)
                            } else {
                                stringResource(R.string.setting_page_tool_approvals_summary, blacklist.size)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            if (blacklist.isNotEmpty()) {
                item {
                    TextButton(
                        onClick = { scope.launch { prefs.clearBlacklist() } },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.setting_page_tool_approvals_revoke_all))
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.setting_page_tool_approvals_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }

            if (toolPalette == null) {
                item {
                    Text(
                        text = stringResource(R.string.setting_page_tool_approvals_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                // ask_user is a request for input, not a capability: it never consults the
                // blacklist, so showing it a switch would be a lie.
                val hits = (if (query.isBlank()) toolPalette.hits else toolPalette.matchAll(query))
                    .filterNot { it.name == ToolApprovalPolicy.ASK_USER_TOOL_NAME }
                if (hits.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.setting_page_tool_approvals_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    toolPalette.groups.forEach { group ->
                        val rows = hits.filter { it.group == group }
                        if (rows.isNotEmpty()) {
                            item(key = "group:${group}") {
                                CardGroup(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    title = { Text(stringResource(localToolTitleRes(group))) },
                                ) {
                                    rows.forEach { hit ->
                                        val floor = ToolApprovalDefaults.isHardFloor(hit.name)
                                        val checked = floor || hit.name in blacklist
                                        item(
                                            headlineContent = { Text(hit.name) },
                                            supportingContent = {
                                                Text(
                                                    text = if (floor) {
                                                        stringResource(R.string.setting_page_tool_approvals_floor_badge)
                                                    } else {
                                                        stringResource(R.string.setting_page_tool_approvals_switch_desc)
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                            trailingContent = {
                                                Switch(
                                                    checked = checked,
                                                    enabled = !floor,
                                                    onCheckedChange = { next ->
                                                        scope.launch { prefs.setBlacklisted(hit.name, next) }
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    TextButton(
                        onClick = {
                            val names = toolPalette.hits
                                .map { it.name }
                                .filter { it in ToolApprovalDefaults.DANGEROUS_TOOLS }
                            scope.launch { prefs.addAllToBlacklist(names) }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.setting_page_tool_approvals_add_recommended))
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.setting_page_tool_approvals_mcp_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
