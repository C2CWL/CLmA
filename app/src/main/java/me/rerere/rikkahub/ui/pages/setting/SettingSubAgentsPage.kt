package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.font.FontWeight
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.subagent.SubAgentDispatchProfile
import me.rerere.rikkahub.subagent.SubAgentDispatchProfileRules
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agentdef.AgentDefinition
import me.rerere.rikkahub.data.agentdef.AgentDefinitionDraft
import me.rerere.rikkahub.data.agentdef.AgentDefinitionRepository
import me.rerere.rikkahub.data.agentdef.LocalToolGroups
import me.rerere.rikkahub.data.agentdef.NamespaceProblem
import me.rerere.rikkahub.data.agentdef.surfaceSummary
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.LocalToolInventory
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalToolPalette
import me.rerere.rikkahub.data.ai.tools.LocalToolPaletteHit
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * P2-06b / P2-06c — the expert library.
 *
 * #36 shipped this screen over the DataStore-backed `Settings.subAgents` list; P2-06b retired that
 * list and backed the same screen with [AgentDefinitionRepository] (its own `agent_definitions.db`).
 * P2-06c is the second half: the screen now edits the expert's whole **surface** — the local-tool
 * groups, the per-tool opt-outs, the MCP servers, the skills and the D9 namespace — instead of
 * only its name / prompt / model.
 *
 * The editing state is an [AgentDefinitionDraft], not an [AgentDefinition], because four of those
 * fields (and the namespace) are **tri-state** on disk: `null` means "inherit the parent
 * assistant", which is different from "own an empty set". A switch cannot express the third state,
 * so the draft keeps it explicit and every row here is a two-step "inherit ⇄ own" header plus the
 * picker that only appears once the user has chosen *own*. The pure half of that lives in
 * `AgentDefinitionDraft` and is unit-tested; what is here is the binding.
 *
 * Name resolution for `subagent_dispatch` is case-insensitive, so a second expert whose name only
 * differs by case would make dispatch ambiguous. That is rejected here (as before) rather than being
 * allowed to reach the resolver at dispatch time — and so is a namespace two experts would share,
 * because that would mix their memory folders.
 */
@Composable
fun SettingSubAgentsPage(
    vm: SettingVM = koinViewModel(),
    agentDefinitionRepository: AgentDefinitionRepository = koinInject(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val definitions by agentDefinitionRepository.definitions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val editState = useEditState<AgentDefinitionDraft> { draft ->
        // A create and an update differ only in whether the row already exists. `update` refuses to
        // insert (a stale id must not resurrect a deleted expert), so the branch is explicit.
        val stored = draft.toDefinition()
        scope.launch {
            if (definitions.any { it.id == stored.id }) {
                agentDefinitionRepository.update(stored)
            } else {
                agentDefinitionRepository.create(
                    name = stored.name,
                    description = stored.description,
                    systemPrompt = stored.systemPrompt,
                    modelId = stored.modelId,
                    enabled = stored.enabled,
                    localTools = stored.localTools,
                    disabledLocalTools = stored.disabledLocalTools,
                    mcpServers = stored.mcpServers,
                    skills = stored.skills,
                    slug = stored.slug,
                    tokenBudget = stored.tokenBudget,
                    id = stored.id,
                )
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_sub_agents_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .floatingToolbarVerticalNestedScroll(
                        expanded = expanded,
                        onExpand = { expanded = true },
                        onCollapse = { expanded = false },
                    ),
                contentPadding = innerPadding + PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Dispatch profile selector — five-level posture (拍板: default NEVER).
                item {
                    val profile = settings.subAgentDispatchProfile
                    val prefs = koinInject<SettingsStore>()
                    val pscope = rememberCoroutineScope()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_sub_agents_dispatch_profile_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.setting_sub_agents_dispatch_profile_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = profile == SubAgentDispatchProfile.NEVER,
                                onClick = { pscope.launch { prefs.update { it.copy(subAgentDispatchProfile = SubAgentDispatchProfile.NEVER) } } },
                                label = { Text(stringResource(R.string.setting_sub_agents_profile_never)) },
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = profile == SubAgentDispatchProfile.ASSIST,
                                onClick = { pscope.launch { prefs.update { it.copy(subAgentDispatchProfile = SubAgentDispatchProfile.ASSIST) } } },
                                label = { Text(stringResource(R.string.setting_sub_agents_profile_assist)) },
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = profile == SubAgentDispatchProfile.NORMAL,
                                onClick = { pscope.launch { prefs.update { it.copy(subAgentDispatchProfile = SubAgentDispatchProfile.NORMAL) } } },
                                label = { Text(stringResource(R.string.setting_sub_agents_profile_normal)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = profile == SubAgentDispatchProfile.BURST,
                                onClick = { pscope.launch { prefs.update { it.copy(subAgentDispatchProfile = SubAgentDispatchProfile.BURST) } } },
                                label = { Text(stringResource(R.string.setting_sub_agents_profile_burst)) },
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = profile == SubAgentDispatchProfile.UNLEASHED,
                                onClick = { pscope.launch { prefs.update { it.copy(subAgentDispatchProfile = SubAgentDispatchProfile.UNLEASHED) } } },
                                label = { Text(stringResource(R.string.setting_sub_agents_profile_unleashed)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        val caps = SubAgentDispatchProfileRules.caps(profile)
                        Text(
                            text = "Global: ${'$'}{caps.globalCap} | Per-assistant: ${'$'}{caps.perAssistantCap} | Max trips: ${'$'}{caps.maxTripsCeiling} | Timeout: ${'$'}{caps.timeoutCeilingSec}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (definitions.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxHeight(0.8f)
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Robot01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.setting_sub_agents_page_empty),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.setting_sub_agents_page_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    editState.open(AgentDefinitionDraft(id = Uuid.random().toString()))
                                },
                            ) {
                                Icon(HugeIcons.Add01, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.setting_sub_agents_page_add))
                            }
                        }
                    }
                } else {
                    items(definitions, key = { it.id }) { definition ->
                        AgentDefinitionCard(
                            definition = definition,
                            onEdit = { editState.open(AgentDefinitionDraft.of(definition)) },
                            onDelete = { scope.launch { agentDefinitionRepository.delete(definition.id) } },
                        )
                    }
                }
            }

            HorizontalFloatingToolbar(
                expanded = expanded,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset),
            ) {
                Button(onClick = {
                    editState.open(AgentDefinitionDraft(id = Uuid.random().toString()))
                }) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(HugeIcons.Add01, null)
                        AnimatedVisibility(expanded) {
                            Row {
                                Text(stringResource(R.string.setting_sub_agents_page_add))
                            }
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { draft ->
            AgentDefinitionEditSheet(
                draft = draft,
                providers = settings.providers,
                mcpServers = settings.mcpServers,
                existingDefinitions = definitions,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it },
            )
        }
    }
}

@Composable
private fun AgentDefinitionCard(
    definition: AgentDefinition,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val summary = definition.surfaceSummary()
    // `listOfNotNull` (not `buildList`) so every `stringResource` is evaluated in the composable
    // scope at this call site rather than inside a builder lambda.
    val surfaceLine = listOfNotNull(
        // D8 - always say what the tool surface is: the owned count, or an explicit
        // "inherits the parent" badge, so an inheriting expert is never left blank.
        if (summary.ownLocalTools) {
            stringResource(R.string.setting_sub_agents_page_badge_tools, summary.localToolCount)
        } else {
            stringResource(R.string.setting_sub_agents_page_surface_inherit)
        },
        if (summary.ownsMcpServers) {
            stringResource(R.string.setting_sub_agents_page_badge_mcp, summary.mcpServerCount)
        } else null,
        if (summary.ownsSkills) {
            stringResource(R.string.setting_sub_agents_page_badge_skills, summary.skillCount)
        } else null,
        summary.namespace?.let {
            stringResource(R.string.setting_sub_agents_page_badge_namespace, it)
        },
    ).joinToString(" · ")

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.setting_sub_agents_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The identity tile doubles as the status light: a live expert wears the primary
                // container, a disabled one goes muted, so the list reads at a glance.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (definition.enabled) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.Robot01,
                        contentDescription = null,
                        tint = if (definition.enabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = definition.name.ifEmpty { stringResource(R.string.setting_sub_agents_page_unnamed) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (definition.description.isNotEmpty()) {
                        Text(
                            text = definition.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (surfaceLine.isNotEmpty()) {
                        Text(
                            text = surfaceLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!definition.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.setting_sub_agents_page_disabled))
                        }
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(HugeIcons.PencilEdit01, stringResource(R.string.setting_sub_agents_page_edit))
                }
            }
        }
    }
}

/**
 * One inheritable surface group: a header ("inherit the parent ⇄ own") and, once the user owns it,
 * the picker. Keeping the header and the picker in one place is what makes the tri-state visible —
 * an empty picker and "not configured" would otherwise look identical.
 */
@Composable
private fun SurfaceGroup(
    title: String,
    subtitle: String,
    own: Boolean,
    ownSummary: String,
    onOwnChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (own) ownSummary else subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // D10b - a two-state segment ("Inherit / Custom") names the tri-state in words, where the
        // old bare switch left the user guessing which way was "off" and what "off" even meant.
        val options = listOf(
            stringResource(R.string.setting_sub_agents_page_surface_mode_inherit),
            stringResource(R.string.setting_sub_agents_page_surface_mode_own),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                val selected = (index == 1) == own
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    selected = selected,
                    onClick = {
                        if (!selected) onOwnChange(index == 1)
                    },
                ) {
                    Text(label)
                }
            }
        }
        if (own) {
            content()
        }
    }
}

/** A compact picker row: a label and a switch. */
@Composable
private fun PickerRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * How many palette hits the sheet lists.
 *
 * The 8-hit cap inside `ToolCatalog` bounds a *response* for a model; a human scrolling a list
 * gets a bigger one, still bounded so a one-letter query cannot compose a thousand rows.
 */
private const val TOOL_PALETTE_MAX_HITS = 30

/**
 * P2-05 - one row of the local tool palette.
 *
 * The palette is a second way to reach the *same* state the "Disabled tools" section below owns:
 * the switch is exactly [AgentDefinitionDraft.toggleDisabledTool]. The badge prints the row's real
 * `ToolCatalogSource` (`LOCAL`) rather than a label this screen made up, so what the user reads is
 * the provenance the catalogue handed back.
 *
 * A tool whose group is off is still listed: that is what a directory is for. You find the tool,
 * you see why it is missing from the surface, and one tap turns its group on.
 */
@Composable
private fun ToolPaletteRow(
    hit: LocalToolPaletteHit,
    groupTitle: String,
    groupEnabled: Boolean,
    disabled: Boolean,
    onDisabledChange: (Boolean) -> Unit,
    onEnableGroup: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = hit.name, style = MaterialTheme.typography.bodyMedium)
                Tag(type = TagType.INFO) { Text(hit.source.name) }
            }
            Text(
                text = "$groupTitle · ${hit.summary}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!groupEnabled) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_sub_agents_page_palette_group_off),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onEnableGroup) {
                        Text(stringResource(R.string.setting_sub_agents_page_palette_enable_group))
                    }
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Switch(checked = disabled, onCheckedChange = onDisabledChange)
            Text(
                text = stringResource(R.string.setting_sub_agents_page_palette_disable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The option → title mapping. A `when` over the sealed [LocalToolOption] with no `else` branch, so
 * adding a tool group to the build fails the build here instead of silently rendering a blank row
 * (the same reason [LocalToolGroups.all] is explicit). The resources are the ones the assistant's
 * own local-tools page uses, so the two screens name a group identically.
 */
internal fun localToolTitleRes(option: LocalToolOption): Int = when (option) {
        LocalToolOption.JavascriptEngine -> R.string.assistant_page_local_tools_javascript_engine_title
        LocalToolOption.TimeInfo -> R.string.assistant_page_local_tools_time_info_title
        LocalToolOption.Clipboard -> R.string.assistant_page_local_tools_clipboard_title
        LocalToolOption.Tts -> R.string.assistant_page_local_tools_tts_title
        LocalToolOption.AskUser -> R.string.assistant_page_local_tools_ask_user_title
        LocalToolOption.Battery -> R.string.assistant_page_local_tools_battery_title
        LocalToolOption.UsageLedger -> R.string.assistant_page_local_tools_usage_ledger_title
        LocalToolOption.AudioInfo -> R.string.assistant_page_local_tools_audio_info_title
        LocalToolOption.TelephonyInfo -> R.string.assistant_page_local_tools_telephony_title
        LocalToolOption.WifiInfo -> R.string.assistant_page_local_tools_wifi_title
        LocalToolOption.Sensors -> R.string.assistant_page_local_tools_sensors_title
        LocalToolOption.StorageInfo -> R.string.assistant_page_local_tools_storage_title
        LocalToolOption.Toast -> R.string.assistant_page_local_tools_toast_title
        LocalToolOption.Notification -> R.string.assistant_page_local_tools_notification_title
        LocalToolOption.Share -> R.string.assistant_page_local_tools_share_title
        LocalToolOption.Torch -> R.string.assistant_page_local_tools_torch_title
        LocalToolOption.Vibrate -> R.string.assistant_page_local_tools_vibrate_title
        LocalToolOption.Brightness -> R.string.assistant_page_local_tools_brightness_title
        LocalToolOption.Volume -> R.string.assistant_page_local_tools_volume_title
        LocalToolOption.Location -> R.string.assistant_page_local_tools_location_title
        LocalToolOption.Contacts -> R.string.assistant_page_local_tools_contacts_title
        LocalToolOption.CallLog -> R.string.assistant_page_local_tools_call_log_title
        LocalToolOption.SmsInbox -> R.string.assistant_page_local_tools_sms_inbox_title
        LocalToolOption.CameraPhoto -> R.string.assistant_page_local_tools_camera_photo_title
        // P2-33 image generation tools
        LocalToolOption.ImageGeneration -> R.string.assistant_page_local_tools_image_generation_title
        // P2-33b video generation tool
        LocalToolOption.VideoGeneration -> R.string.assistant_page_local_tools_video_generation_title
        LocalToolOption.MicRecorder -> R.string.assistant_page_local_tools_mic_recorder_title
        LocalToolOption.SpeechToText -> R.string.assistant_page_local_tools_speech_to_text_title
        LocalToolOption.Fingerprint -> R.string.assistant_page_local_tools_fingerprint_title
        LocalToolOption.NotificationListener -> R.string.assistant_page_local_tools_notifications_title
        LocalToolOption.MediaPlayer -> R.string.assistant_page_local_tools_media_player_title
        LocalToolOption.MediaScanner -> R.string.assistant_page_local_tools_media_scanner_title
        LocalToolOption.Download -> R.string.assistant_page_local_tools_download_title
        LocalToolOption.CronJobs -> R.string.assistant_page_local_tools_cron_jobs_title
        LocalToolOption.Files -> R.string.assistant_page_local_tools_files_title
        LocalToolOption.Ssh -> R.string.assistant_page_local_tools_ssh_title
        LocalToolOption.TelegramBot -> R.string.assistant_page_local_tools_telegram_title
        LocalToolOption.McpControl -> R.string.assistant_page_local_tools_mcp_control_title
        LocalToolOption.ExternalAutomation -> R.string.assistant_page_local_tools_external_automation_title
        LocalToolOption.Reliability -> R.string.assistant_page_local_tools_reliability_title
        LocalToolOption.SubAgents -> R.string.assistant_page_local_tools_sub_agents_title
        LocalToolOption.ModelControl -> R.string.assistant_page_local_tools_model_control_title
        LocalToolOption.CostGuards -> R.string.assistant_page_local_tools_cost_guards_title
        LocalToolOption.Workflows -> R.string.assistant_page_local_tools_workflows_title
        LocalToolOption.SkillImport -> R.string.assistant_page_local_tools_skill_import_title
        LocalToolOption.JsSkills -> R.string.assistant_page_local_tools_js_skills_title
        LocalToolOption.SystemIntents -> R.string.assistant_page_local_tools_system_intents_title
        LocalToolOption.Browser -> R.string.assistant_page_local_tools_browser_title
        LocalToolOption.SmsSend -> R.string.assistant_page_local_tools_sms_send_title
        LocalToolOption.Wallpaper -> R.string.assistant_page_local_tools_wallpaper_title
        LocalToolOption.Keystore -> R.string.assistant_page_local_tools_keystore_title
        LocalToolOption.Nfc -> R.string.assistant_page_local_tools_nfc_title
        LocalToolOption.ExternalStorage -> R.string.assistant_page_local_tools_external_storage_title
        LocalToolOption.Archive -> R.string.assistant_page_local_tools_archive_title
        LocalToolOption.Shizuku -> R.string.assistant_page_local_tools_shizuku_title
        LocalToolOption.ScreenAutomation -> R.string.assistant_page_local_tools_screen_automation_title
        LocalToolOption.AppLauncher -> R.string.assistant_page_local_tools_app_launcher_title
        LocalToolOption.Termux -> R.string.assistant_page_local_tools_termux_title
        LocalToolOption.KeyboardControl -> R.string.assistant_page_local_tools_keyboard_title
        LocalToolOption.VirtualDisplay -> R.string.assistant_page_local_tools_virtual_display_title
}

/**
 * D10b - one titled card per concern in the edit sheet (identity, model, prompt, surface), so a
 * long form reads as a few blocks instead of one undifferentiated scroll.
 */
@Composable
private fun SheetSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun AgentDefinitionEditSheet(
    draft: AgentDefinitionDraft,
    providers: List<ProviderSetting>,
    mcpServers: List<McpServerConfig>,
    existingDefinitions: List<AgentDefinition>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (AgentDefinitionDraft) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val nameDuplicate = draft.nameClash(existingDefinitions)
    val namespaceProblem = draft.namespaceProblem(existingDefinitions)

    // Skills are read from disk once when the sheet opens. The list is small and only rendered
    // when the user asks for a custom skill set, so a one-shot load beats a flow here.
    val skillManager = koinInject<SkillManager>()
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }
    LaunchedEffect(Unit) {
        skills = withContext(Dispatchers.IO) {
            runCatching { skillManager.listSkills() }.getOrDefault(emptyList())
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_sub_agents_page_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---- identity ------------------------------------------------------------
                SheetSection(title = stringResource(R.string.setting_sub_agents_page_section_identity)) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { onEdit(draft.copy(name = it)) },
                        label = { Text(stringResource(R.string.setting_sub_agents_page_name)) },
                        singleLine = true,
                        isError = nameDuplicate,
                        supportingText = if (nameDuplicate) {
                            { Text(stringResource(R.string.setting_sub_agents_page_name_duplicate)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = { onEdit(draft.copy(description = it)) },
                        label = { Text(stringResource(R.string.setting_sub_agents_page_description)) },
                        supportingText = { Text(stringResource(R.string.setting_sub_agents_page_description_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    FormItem(
                        label = { Text(stringResource(R.string.setting_sub_agents_page_enabled)) },
                        tail = {
                            Switch(
                                checked = draft.enabled,
                                onCheckedChange = { onEdit(draft.copy(enabled = it)) },
                            )
                        },
                    )
                }

                // ---- model and the orchestration ceiling ---------------------------------
                SheetSection(title = stringResource(R.string.setting_sub_agents_page_section_model)) {
                    FormItem(
                        label = { Text(stringResource(R.string.setting_sub_agents_page_model)) },
                        description = { Text(stringResource(R.string.setting_sub_agents_page_model_desc)) },
                        content = {
                            ModelSelector(
                                // The store keeps the model id as its canonical Uuid string; the picker
                                // speaks Uuid. A value that no longer parses (hand-edited data) reads as
                                // "no model chosen" here rather than crashing the sheet.
                                modelId = draft.modelId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                                providers = providers,
                                type = ModelType.CHAT,
                                allowClear = true,
                                onSelect = { model ->
                                    // ModelSelector's clear button calls onSelect(Model()), whose
                                    // default modelId is "" - no real model ever has a blank
                                    // provider model id, so that's the clear signal.
                                    onEdit(
                                        draft.copy(
                                            modelId = model.id
                                                .takeIf { model.modelId.isNotBlank() }
                                                ?.toString(),
                                        ),
                                    )
                                },
                            )
                        },
                    )

                    // P2-07 — the expert's own orchestration ceiling. A blank field stores null,
                    // which means "inherit the parent assistant's budget"; a positive number
                    // overrides it for any dispatch that names this expert.
                    OutlinedTextField(
                        value = draft.tokenBudget?.toString() ?: "",
                        onValueChange = { text ->
                            onEdit(
                                draft.copy(
                                    tokenBudget = if (text.isBlank()) {
                                        null
                                    } else {
                                        text.toLongOrNull()?.takeIf { it > 0 }
                                    },
                                ),
                            )
                        },
                        label = { Text(stringResource(R.string.setting_sub_agents_page_token_budget)) },
                        supportingText = { Text(stringResource(R.string.setting_sub_agents_page_token_budget_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ---- system prompt -------------------------------------------------------
                SheetSection(title = stringResource(R.string.setting_sub_agents_page_system_prompt)) {
                    OutlinedTextField(
                        value = draft.systemPrompt,
                        onValueChange = { onEdit(draft.copy(systemPrompt = it)) },
                        label = { Text(stringResource(R.string.setting_sub_agents_page_system_prompt)) },
                        supportingText = { Text(stringResource(R.string.setting_sub_agents_page_system_prompt_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        minLines = 4,
                    )
                }

                // ---- tool surface --------------------------------------------------------
                SheetSection(title = stringResource(R.string.setting_sub_agents_page_surface_title)) {
                    Text(
                        text = stringResource(R.string.setting_sub_agents_page_surface_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // ---- P2-05 palette: the local tool directory, searchable ------------------
                    // Built from the live factory, one group at a time: grouping is what gives every
                    // row an owner ("which group is this tool in?") without re-deriving the factory's
                    // if/else ladder here. 56 calls, all cheap object construction, once per sheet.
                    // A failure hides the section instead of taking the sheet down with it.
                    val localToolFactory = koinInject<LocalTools>()
                    val toolPalette = remember {
                        runCatching {
                            LocalToolPalette.build(
                                LocalToolGroups.all.map { group ->
                                    LocalToolInventory(
                                        group = group,
                                        tools = localToolFactory.getTools(listOf(group)),
                                    )
                                },
                            )
                        }.getOrNull()
                    }
                    var paletteQuery by rememberSaveable { mutableStateOf("") }
                    if (toolPalette != null) {
                        // An inherited expert has no list to read: the sheet cannot see the parent
                        // assistant's groups, so it treats "inherit" as "everything on" - the same
                        // convention the per-tool opt-out list below uses.
                        val effectiveGroups = draft.localTools ?: LocalToolGroups.all
                        Text(
                            text = stringResource(R.string.setting_sub_agents_page_palette_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.setting_sub_agents_page_palette_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = paletteQuery,
                            onValueChange = { paletteQuery = it },
                            label = { Text(stringResource(R.string.setting_sub_agents_page_palette_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (paletteQuery.isBlank()) {
                            Text(
                                text = stringResource(
                                    R.string.setting_sub_agents_page_palette_summary,
                                    toolPalette.groups.size,
                                    toolPalette.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val hits = toolPalette.matchAll(paletteQuery).take(TOOL_PALETTE_MAX_HITS)
                            if (hits.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.setting_sub_agents_page_palette_no_match),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                hits.forEach { hit ->
                                    ToolPaletteRow(
                                        hit = hit,
                                        groupTitle = stringResource(localToolTitleRes(hit.group)),
                                        groupEnabled = effectiveGroups.contains(hit.group),
                                        disabled = draft.disabledLocalTools.orEmpty().contains(hit.name),
                                        onDisabledChange = { disabled ->
                                            onEdit(draft.toggleDisabledTool(hit.name, disabled))
                                        },
                                        onEnableGroup = {
                                            // Owning an empty set and adding exactly this group: the
                                            // user asked for one group, not for a guessed starting set.
                                            onEdit(draft.ownLocalTools().toggleLocalTool(hit.group, true))
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // ---- local tool groups ---------------------------------------------------
                    SurfaceGroup(
                        title = stringResource(R.string.setting_sub_agents_page_surface_tools),
                        // D9 - in inherit mode this is the parent's WHOLE surface, decided at
                        // dispatch time, not "no tools".
                        subtitle = stringResource(R.string.setting_sub_agents_page_surface_inherit_tools),
                        own = draft.ownsLocalTools,
                        ownSummary = stringResource(
                            R.string.setting_sub_agents_page_surface_own_count,
                            draft.localTools?.size ?: 0,
                        ),
                        onOwnChange = { own ->
                            onEdit(if (own) draft.ownLocalTools() else draft.inheritLocalTools())
                        },
                    ) {
                        LocalToolGroups.all.forEach { option ->
                            PickerRow(
                                label = stringResource(localToolTitleRes(option)),
                                checked = draft.localTools.orEmpty().contains(option),
                                onCheckedChange = { onEdit(draft.toggleLocalTool(option, it)) },
                            )
                        }
                    }

                    // ---- per-tool opt-outs ---------------------------------------------------
                    // P2-02 semantics: a name in this set stays hidden even while its group is on.
                    // D10b - this used to re-list every tool in the catalogue under a second set of
                    // switches, which duplicated the palette above and buried the real question
                    // ("what did I switch off?"). It now shows only the names actually excluded;
                    // the palette is where a tool gets excluded in the first place.
                    SurfaceGroup(
                        title = stringResource(R.string.setting_sub_agents_page_surface_disabled_tools),
                        subtitle = stringResource(R.string.setting_sub_agents_page_surface_inherit),
                        own = draft.ownsDisabledTools,
                        ownSummary = stringResource(
                            R.string.setting_sub_agents_page_surface_disabled_count,
                            draft.disabledLocalTools?.size ?: 0,
                        ),
                        onOwnChange = { own ->
                            onEdit(if (own) draft.ownDisabledTools() else draft.inheritDisabledTools())
                        },
                    ) {
                        val excluded = draft.disabledLocalTools.orEmpty().sorted()
                        if (excluded.isEmpty()) {
                            Text(
                                text = stringResource(R.string.setting_sub_agents_page_surface_no_disabled),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            excluded.forEach { toolName ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = toolName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextButton(
                                        onClick = { onEdit(draft.toggleDisabledTool(toolName, false)) },
                                    ) {
                                        Text(stringResource(R.string.setting_sub_agents_page_surface_restore))
                                    }
                                }
                            }
                        }
                    }

                    // ---- MCP servers ---------------------------------------------------------
                    val enabledServers = mcpServers.filter { it.commonOptions.enable }
                    SurfaceGroup(
                        title = stringResource(R.string.setting_sub_agents_page_surface_mcp),
                        subtitle = stringResource(R.string.setting_sub_agents_page_surface_inherit),
                        own = draft.ownsMcpServers,
                        ownSummary = stringResource(
                            R.string.setting_sub_agents_page_surface_own_count,
                            draft.mcpServers?.size ?: 0,
                        ),
                        onOwnChange = { own ->
                            onEdit(if (own) draft.ownMcpServers() else draft.inheritMcpServers())
                        },
                    ) {
                        if (enabledServers.isEmpty()) {
                            Text(
                                text = stringResource(R.string.setting_sub_agents_page_surface_no_mcp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            enabledServers.forEach { server ->
                                val id = server.id.toString()
                                PickerRow(
                                    label = server.commonOptions.name,
                                    checked = draft.mcpServers.orEmpty().contains(id),
                                    onCheckedChange = { onEdit(draft.toggleMcpServer(id, it)) },
                                )
                            }
                        }
                    }

                    // ---- skills --------------------------------------------------------------
                    SurfaceGroup(
                        title = stringResource(R.string.setting_sub_agents_page_surface_skills),
                        subtitle = stringResource(R.string.setting_sub_agents_page_surface_inherit),
                        own = draft.ownsSkills,
                        ownSummary = stringResource(
                            R.string.setting_sub_agents_page_surface_own_count,
                            draft.skills?.size ?: 0,
                        ),
                        onOwnChange = { own ->
                            onEdit(if (own) draft.ownSkills() else draft.inheritSkills())
                        },
                    ) {
                        if (skills.isEmpty()) {
                            Text(
                                text = stringResource(R.string.setting_sub_agents_page_surface_no_skills),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            skills.forEach { skill ->
                                PickerRow(
                                    label = skill.name,
                                    description = skill.description.ifBlank { null },
                                    checked = draft.skills.orEmpty().contains(skill.name),
                                    onCheckedChange = { onEdit(draft.toggleSkill(skill.name, it)) },
                                )
                            }
                        }
                    }

                    // ---- D9 namespace --------------------------------------------------------
                    SurfaceGroup(
                        title = stringResource(R.string.setting_sub_agents_page_surface_namespace),
                        subtitle = stringResource(R.string.setting_sub_agents_page_surface_namespace_off),
                        own = draft.namespaceOn,
                        ownSummary = draft.namespacePreview()
                            ?: stringResource(R.string.setting_sub_agents_page_surface_namespace_unsaved),
                        onOwnChange = { own ->
                            onEdit(
                                if (own) draft.withNamespace(draft.suggestedNamespace())
                                else draft.clearNamespace(),
                            )
                        },
                    ) {
                        OutlinedTextField(
                            value = draft.namespaceInput.orEmpty(),
                            onValueChange = { onEdit(draft.withNamespace(it)) },
                            label = { Text(stringResource(R.string.setting_sub_agents_page_surface_namespace_label)) },
                            singleLine = true,
                            isError = namespaceProblem == NamespaceProblem.EMPTY,
                            supportingText = {
                                Text(stringResource(R.string.setting_sub_agents_page_surface_namespace_hint))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        draft.namespacePreview()?.let { preview ->
                            Text(
                                text = stringResource(R.string.setting_sub_agents_page_surface_namespace_preview, preview),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when (namespaceProblem) {
                            NamespaceProblem.EMPTY -> Text(
                                text = stringResource(R.string.setting_sub_agents_page_surface_namespace_error_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )

                            NamespaceProblem.DUPLICATE -> Text(
                                text = stringResource(
                                    R.string.setting_sub_agents_page_surface_namespace_error_duplicate,
                                    draft.namespaceSlug().orEmpty(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )

                            null -> Unit
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.setting_sub_agents_page_cancel))
                }
                Button(
                    onClick = onConfirm,
                    enabled = draft.canSave(existingDefinitions),
                ) {
                    Text(stringResource(R.string.setting_sub_agents_page_confirm))
                }
            }
        }
    }
}
