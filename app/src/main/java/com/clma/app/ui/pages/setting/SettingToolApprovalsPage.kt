package com.clma.app.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.clma.app.ui.components.ai.ToolDisplayHeadline
import com.clma.app.ui.components.ai.ToolDisplaySupporting
import com.clma.app.ui.components.ai.rememberToolDisplaySearchText
import com.clma.app.ui.components.ai.toolNameWithId
import com.clma.app.R
import com.clma.app.data.agentdef.LocalToolGroups
import com.clma.app.data.ai.tools.LocalToolInventory
import com.clma.app.data.ai.tools.LocalToolPalette
import com.clma.app.data.ai.tools.LocalTools
import com.clma.app.data.ai.tools.ToolApprovalDefaults
import com.clma.app.data.ai.tools.ApprovalMode
import com.clma.app.data.ai.tools.ToolApprovalPolicy
import com.clma.app.data.preferences.ToolApprovalPreferences
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.theme.CustomColors
import com.clma.app.utils.plus
import org.koin.compose.koinInject

/**
 * Tool authorization, blacklist era.
 *
 * Every tool runs without asking by default; this screen is where the user says which ones must
 * ask. The list is built from the live local-tool factory (one [LocalToolPalette] group at a
 * time), so the names shown can never drift from the surface the model actually receives, and a
 * switch is simply "is this tool on the blacklist".
 *
 * Two things ask by default: the floor ([ToolApprovalDefaults.NO_ALWAYS_ALLOW] —
 * privilege-escalation surfaces, one row each, tagged "high risk") and every opaque `mcp__*`
 * relayed tool (one switch in the "MCP server tools" card). Turning either OFF opens
 * [FloorWaiverDialog] — a risk list plus a liability disclaimer the user must tick before the
 * red confirm button enables; dismissing changes nothing. Turning them back ON is immediate.
 * The three-mode selector at the top picks the posture: Auto (everything but the un-waived
 * floor auto-approves), Semi (blacklist decides), Manual (every call asks).
 */
@Composable
fun SettingToolApprovalsPage() {
    val prefs: ToolApprovalPreferences = koinInject()
    val blacklist by prefs.blacklistFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val mode by prefs.modeFlow.collectAsStateWithLifecycle(initialValue = ApprovalMode.MANUAL)
    val floorWaived by prefs.floorWaivedFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val mcpRequireApproval by prefs.mcpRequireApprovalFlow.collectAsStateWithLifecycle(initialValue = true)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var pendingAuto by remember { mutableStateOf(false) }
    // Which default-ask approval the user is about to turn off; null = no dialog. A tool name
    // from NO_ALWAYS_ALLOW, or [MCP_WAIVER_TARGET] for the MCP card's switch.
    var pendingWaiver by remember { mutableStateOf<String?>(null) }
    pendingWaiver?.let { target ->
        FloorWaiverDialog(
            target = target,
            onConfirm = {
                pendingWaiver = null
                scope.launch {
                    if (target == MCP_WAIVER_TARGET) {
                        prefs.setMcpRequireApproval(false)
                    } else {
                        prefs.setFloorWaived(target, true)
                    }
                }
            },
            onDismiss = { pendingWaiver = null },
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
    val displaySearchText = rememberToolDisplaySearchText()

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
                // Ranked id/summary matches first, then tools whose display name or
                // description (e.g. Chinese) contains the query.
                val hits = (if (query.isBlank()) {
                    toolPalette.hits
                } else {
                    val ranked = toolPalette.matchAll(query)
                    val rankedNames = ranked.mapTo(HashSet()) { it.name }
                    val q = query.trim()
                    ranked + toolPalette.hits.filter {
                        it.name !in rankedNames &&
                            displaySearchText[it.name]?.contains(q, ignoreCase = true) == true
                    }
                })
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
                                        // Local tools only: relayed mcp__* tools are covered by the MCP card.
                                        val floor = hit.name in ToolApprovalDefaults.NO_ALWAYS_ALLOW
                                        val waived = floor && hit.name in floorWaived
                                        val checked = if (floor) !waived else hit.name in blacklist
                                        item(
                                            headlineContent = {
                                                ToolDisplayHeadline(
                                                    toolName = hit.name,
                                                    tag = if (floor) {
                                                        { HighRiskTag() }
                                                    } else {
                                                        null
                                                    },
                                                )
                                            },
                                            supportingContent = {
                                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    ToolDisplaySupporting(toolName = hit.name)
                                                    when {
                                                        waived -> WaivedText()
                                                        else -> Text(
                                                            text = if (floor) {
                                                                stringResource(R.string.setting_page_tool_approvals_floor_badge)
                                                            } else {
                                                                stringResource(R.string.setting_page_tool_approvals_switch_desc)
                                                            },
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            },
                                            trailingContent = {
                                                Switch(
                                                    checked = checked,
                                                    onCheckedChange = { next ->
                                                        if (!floor) {
                                                            scope.launch { prefs.setBlacklisted(hit.name, next) }
                                                        } else if (next) {
                                                            // Restoring approval is the safe direction: no dialog.
                                                            scope.launch { prefs.setFloorWaived(hit.name, false) }
                                                        } else {
                                                            pendingWaiver = hit.name
                                                        }
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
                            // Also restores every waived default (floor waivers, MCP approval).
                            scope.launch { prefs.restoreRecommended(names) }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.setting_page_tool_approvals_add_recommended))
                    }
                }
            }

            item(key = "mcp-approval-card") {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_tool_approvals_mcp_group)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_tool_approvals_mcp_switch)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (!mcpRequireApproval) WaivedText()
                                Text(
                                    text = stringResource(R.string.setting_page_tool_approvals_mcp_switch_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = mcpRequireApproval,
                                onCheckedChange = { next ->
                                    if (next) {
                                        scope.launch { prefs.setMcpRequireApproval(true) }
                                    } else {
                                        pendingWaiver = MCP_WAIVER_TARGET
                                    }
                                },
                            )
                        },
                    )
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

/** Sentinel [FloorWaiverDialog] target for the "MCP server tools ask" switch. Not a tool name:
 *  real tool names never contain spaces or angle brackets. */
internal const val MCP_WAIVER_TARGET = "<mcp relayed tools>"

/** Small red "high risk" chip next to a default-ask floor tool's name. */
@Composable
internal fun HighRiskTag() {
    Text(
        text = stringResource(R.string.setting_page_tool_approvals_high_risk_tag),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Red "approval turned off (at your own risk)" supporting line for a waived default. */
@Composable
private fun WaivedText() {
    Text(
        text = stringResource(R.string.setting_page_tool_approvals_floor_waived),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.error,
    )
}

/** Tool-specific risk line for [FloorWaiverDialog]. */
@Composable
internal fun waiverRiskText(target: String): String = stringResource(
    when (target) {
        MCP_WAIVER_TARGET -> R.string.setting_page_tool_approvals_waiver_risk_mcp_relayed
        "mcp_add", "mcp_update" -> R.string.setting_page_tool_approvals_waiver_risk_mcp_install
        "eval_javascript" -> R.string.setting_page_tool_approvals_waiver_risk_eval_js
        "skill_install_from_url", "skill_install_from_text" ->
            R.string.setting_page_tool_approvals_waiver_risk_skill_install
        "browser_eval_js" -> R.string.setting_page_tool_approvals_waiver_risk_browser_eval_js
        "keystore_generate_key" -> R.string.setting_page_tool_approvals_waiver_risk_keystore_generate
        "keystore_decrypt" -> R.string.setting_page_tool_approvals_waiver_risk_keystore_decrypt
        "nfc_write_tag" -> R.string.setting_page_tool_approvals_waiver_risk_nfc_write
        "grant_directory_access" -> R.string.setting_page_tool_approvals_waiver_risk_grant_directory
        else -> R.string.setting_page_tool_approvals_waiver_risk_generic
    }
)

/**
 * Risk warning + liability disclaimer shown before a default-ask approval (a floor tool, or all
 * relayed MCP tools) is turned off. The red confirm button stays disabled until the user ticks
 * the disclaimer; dismissing or cancelling leaves the setting unchanged.
 */
@Composable
private fun FloorWaiverDialog(
    target: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var accepted by remember(target) { mutableStateOf(false) }
    val displayName = if (target == MCP_WAIVER_TARGET) {
        stringResource(R.string.setting_page_tool_approvals_mcp_group)
    } else {
        toolNameWithId(target)
    }
    val bodyTemplate = stringResource(R.string.setting_page_tool_approvals_waiver_dialog_body, displayName)
    val nameStart = bodyTemplate.indexOf(displayName)
    val body = buildAnnotatedString {
        if (nameStart < 0) {
            append(bodyTemplate)
        } else {
            append(bodyTemplate.substring(0, nameStart))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(displayName)
            }
            append(bodyTemplate.substring(nameStart + displayName.length))
        }
    }
    val risks = listOf(
        waiverRiskText(target) to true,
        stringResource(R.string.setting_page_tool_approvals_waiver_risk_remote) to false,
        stringResource(R.string.setting_page_tool_approvals_waiver_risk_injection) to false,
        stringResource(R.string.setting_page_tool_approvals_waiver_risk_unattended) to false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text("⚠", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error) },
        title = {
            Text(
                text = stringResource(R.string.setting_page_tool_approvals_waiver_dialog_title),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                risks.forEach { (line, primary) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (primary) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (primary) FontWeight.Medium else null,
                            color = if (primary) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                        .clickable { accepted = !accepted }
                        .padding(8.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Checkbox(
                        checked = accepted,
                        onCheckedChange = { accepted = it },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.error),
                    )
                    Text(
                        text = stringResource(R.string.setting_page_tool_approvals_waiver_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = accepted) {
                Text(
                    text = stringResource(R.string.setting_page_tool_approvals_waiver_confirm),
                    color = if (accepted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_page_tool_approvals_waiver_cancel))
            }
        },
    )
}
