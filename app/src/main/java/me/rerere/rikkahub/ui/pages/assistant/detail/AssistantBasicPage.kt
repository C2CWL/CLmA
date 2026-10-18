package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.ai.tools.ToolSurfaceMode
import me.rerere.rikkahub.subagent.SubAgentDefaults
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.TagsInput
import me.rerere.rikkahub.ui.components.ui.AssistantAvatar
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Tag as DataTag

@Composable
fun AssistantBasicPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_basic))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantBasicContent(
            innerPadding = innerPadding,
            assistant = assistant,
            providers = providers,
            tags = tags,
            workspaces = workspaces,
            onUpdate = { vm.update(it) },
            vm = vm
        )
    }
}

@Composable
internal fun AssistantBasicContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    tags: List<DataTag>,
    workspaces: List<WorkspaceEntity>,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AssistantAvatar(
                assistant = assistant,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = { avatar ->
                    onUpdate(
                        assistant.copy(
                            avatar = avatar
                        )
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_name))
                },
                modifier = Modifier.padding(8.dp),

                ) {
                OutlinedTextField(
                    value = assistant.name,
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                name = it
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_tags))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                TagsInput(
                    value = assistant.tags,
                    tags = tags,
                    onValueChange = { tagIds, tagList ->
                        vm.updateTags(tagIds, tagList)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_workspace))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_workspace_desc))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                val selectedWorkspace = workspaces.find { it.id == assistant.workspaceId?.toString() }
                Select(
                    options = listOf<WorkspaceEntity?>(null) + workspaces,
                    selectedOption = selectedWorkspace,
                    onOptionSelected = { workspace ->
                        onUpdate(
                            assistant.copy(
                                workspaceId = workspace?.id?.let { Uuid.parse(it) }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { workspace ->
                        workspace?.name ?: stringResource(R.string.workspace_no_binding)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useAssistantAvatar,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useAssistantAvatar = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_chat_model))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_chat_model_desc))
                },
                content = {
                    ModelSelector(
                        modelId = assistant.chatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = {
                            onUpdate(
                                assistant.copy(
                                    chatModelId = it.id
                                )
                            )
                        },
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_temperature))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_temperature_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.temperature != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    temperature = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                if (assistant.temperature != null) {
                    var temperatureInput by remember(assistant.id) {
                        mutableStateOf(assistant.temperature.toString())
                    }
                    val temperatureValue = temperatureInput.toFloatOrNull()
                    OutlinedTextField(
                        value = temperatureInput,
                        onValueChange = { value ->
                            temperatureInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { temperature ->
                                onUpdate(
                                    assistant.copy(
                                        temperature = temperature
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = temperatureValue == null || temperatureValue !in 0f..2f,
                        supportingText = {
                            Text("0 - 2")
                        }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_top_p))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_top_p_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.topP != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    topP = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                assistant.topP?.let { topP ->
                    var topPInput by remember(assistant.id) {
                        mutableStateOf(topP.toString())
                    }
                    val topPValue = topPInput.toFloatOrNull()
                    OutlinedTextField(
                        value = topPInput,
                        onValueChange = { value ->
                            topPInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { nextTopP ->
                                onUpdate(
                                    assistant.copy(
                                        topP = nextTopP
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = topPValue == null || topPValue !in 0f..1f,
                        supportingText = {
                            Text("0 - 1")
                        }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_context_message_limit))
                },
                description = {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_limit_desc),
                    )
                }
            ) {
                var contextMessageLimitInput by remember(
                    assistant.id,
                    assistant.contextMessageLimit
                ) {
                    mutableStateOf(assistant.contextMessageLimit.toString())
                }
                var contextMessageLimitFocused by remember(assistant.id) {
                    mutableStateOf(false)
                }
                val focusManager = LocalFocusManager.current

                fun commitContextMessageLimit() {
                    val value = contextMessageLimitInput.toIntOrNull()
                    if (value == null) {
                        contextMessageLimitInput = assistant.contextMessageLimit.toString()
                        return
                    }

                    contextMessageLimitInput = value.toString()
                    if (value != assistant.contextMessageLimit) {
                        onUpdate(assistant.copy(contextMessageLimit = value))
                    }
                }

                OutlinedTextField(
                    value = contextMessageLimitInput,
                    onValueChange = { input ->
                        if (input.all(Char::isDigit) &&
                            (input.isEmpty() || input.toIntOrNull() != null)
                        ) {
                            contextMessageLimitInput = input
                            input.toIntOrNull()
                                ?.takeIf { it != assistant.contextMessageLimit }
                                ?.let { onUpdate(assistant.copy(contextMessageLimit = it)) }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (contextMessageLimitFocused && !focusState.isFocused) {
                                commitContextMessageLimit()
                            }
                            contextMessageLimitFocused = focusState.isFocused
                        },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    singleLine = true,
                    supportingText = {
                        Text(
                            stringResource(R.string.assistant_page_context_message_limit_hint)
                        )
                    }
                )

                if (assistant.contextMessageLimit > 0) {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_limit_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_stream_output))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_stream_output_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.streamOutput,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    streamOutput = it
                                )
                            )
                        }
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_fast_path_router))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_fast_path_router_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.fastPathRouterEnabled,
                        onCheckedChange = {
                            onUpdate(assistant.copy(fastPathRouterEnabled = it))
                        }
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_thinking_budget))
                },
            ) {
                ReasoningButton(
                    reasoningLevel = assistant.reasoningLevel,
                    onUpdateReasoningLevel = { level ->
                        onUpdate(assistant.copy(reasoningLevel = level))
                    }
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_max_tokens))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_max_tokens_desc))
                }
            ) {
                OutlinedTextField(
                    value = assistant.maxTokens?.toString() ?: "",
                    onValueChange = { text ->
                        val tokens = if (text.isBlank()) {
                            null
                        } else {
                            text.toIntOrNull()?.takeIf { it > 0 }
                        }
                        onUpdate(
                            assistant.copy(
                                maxTokens = tokens
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_max_tokens_no_limit))
                    },
                    supportingText = {
                        if (assistant.maxTokens != null) {
                            Text(stringResource(R.string.assistant_page_max_tokens_limit, assistant.maxTokens))
                        } else {
                            Text(stringResource(R.string.assistant_page_max_tokens_no_token_limit))
                        }
                    }
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_tool_result_budget))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_tool_result_budget_desc))
                }
            ) {
                OutlinedTextField(
                    value = assistant.toolResultMaxTokens?.toString() ?: "",
                    onValueChange = { text ->
                        val tokens = if (text.isBlank()) {
                            null
                        } else {
                            text.toIntOrNull()?.takeIf { it > 0 }
                        }
                        onUpdate(
                            assistant.copy(
                                toolResultMaxTokens = tokens
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_tool_result_budget_placeholder))
                    },
                    supportingText = {
                        if (assistant.toolResultMaxTokens != null) {
                            Text(
                                stringResource(
                                    R.string.assistant_page_tool_result_budget_active,
                                    assistant.toolResultMaxTokens,
                                )
                            )
                        } else {
                            Text(stringResource(R.string.assistant_page_tool_result_budget_off))
                        }
                    }
                )
            }
            HorizontalDivider()
            // P2-24 (D7) - new sub-agent conversations are filed into this folder, which keeps
            // them out of the main chat list. The folder is created in the chat drawer (its
            // dialog carries the matching "sub-agent archive" switch); this is the second
            // entry point to the same single, per-assistant slot.
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_sub_agent_archive))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_sub_agent_archive_desc))
                },
                tail = {
                    val folders by vm.folders.collectAsStateWithLifecycle()
                    val archiveFolderId by vm.subAgentArchiveFolderId.collectAsStateWithLifecycle()
                    var folderMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(
                            onClick = { folderMenuExpanded = true },
                            enabled = folders.isNotEmpty(),
                        ) {
                            Text(
                                folders.firstOrNull { it.id == archiveFolderId }?.name
                                    ?: stringResource(R.string.assistant_page_sub_agent_archive_none)
                            )
                        }
                        DropdownMenu(
                            expanded = folderMenuExpanded,
                            onDismissRequest = { folderMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.assistant_page_sub_agent_archive_none)) },
                                onClick = {
                                    vm.setSubAgentArchiveFolder(null)
                                    folderMenuExpanded = false
                                },
                            )
                            folders.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder.name) },
                                    onClick = {
                                        vm.setSubAgentArchiveFolder(folder.id)
                                        folderMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_tool_retry))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_tool_retry_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.enableToolExecutionRetry,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    enableToolExecutionRetry = it
                                )
                            )
                        }
                    )
                }
            )
        }

        // P2-28 (D4): protocol-level and experimental switches get their own card so
        // they stop reading as ordinary assistant settings. All four default to off.
        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_advanced_section),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.assistant_page_advanced_section_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_compact_context))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_compact_context_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.enableCompactContextTool,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    enableCompactContextTool = it
                                )
                            )
                        }
                    )
                }
            )
            if (assistant.enableCompactContextTool) {
                val reminderPercent = assistant.contextBudgetReminderPercent.coerceIn(5, 95)
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_context_reminder_threshold))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_context_reminder_threshold_desc))
                    },
                ) {
                    Slider(
                        value = reminderPercent.toFloat(),
                        onValueChange = { value ->
                            onUpdate(
                                assistant.copy(
                                    contextBudgetReminderPercent = (value / 5f).roundToInt() * 5
                                )
                            )
                        },
                        valueRange = 5f..95f,
                        steps = 17,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_context_reminder_threshold_value,
                            reminderPercent
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_tool_surface_mode))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_tool_surface_mode_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.toolSurfaceMode == ToolSurfaceMode.PROGRESSIVE_CATALOG,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    toolSurfaceMode = if (it) {
                                        ToolSurfaceMode.PROGRESSIVE_CATALOG
                                    } else {
                                        ToolSurfaceMode.DIRECT
                                    }
                                )
                            )
                        }
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_subagent_context_refs))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_subagent_context_refs_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.enableSubAgentContextRefs,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    enableSubAgentContextRefs = it
                                )
                            )
                        }
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_subagent_tool_surface))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_subagent_tool_surface_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.enableSubAgentToolSurface,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    enableSubAgentToolSurface = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_gradient_background))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_gradient_background_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useGradientBackground,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useGradientBackground = it
                                )
                            )
                        }
                    )
                }
            )

            if (!assistant.useGradientBackground) {
                HorizontalDivider()

                BackgroundPicker(
                    modifier = Modifier.padding(8.dp),
                    background = assistant.background,
                    darkBackground = assistant.darkBackground,
                    lightBackground = assistant.lightBackground,
                    backgroundOpacity = assistant.backgroundOpacity,
                    onUpdate = { slot, value ->
                        onUpdate(
                            when (slot) {
                                BackgroundSlot.COMMON -> assistant.copy(background = value)
                                BackgroundSlot.DARK -> assistant.copy(darkBackground = value)
                                BackgroundSlot.LIGHT -> assistant.copy(lightBackground = value)
                            }
                        )
                    }
                )

                // Shown whenever any wallpaper is set, so a stored GENERAL can always be switched
                // back, even after both theme wallpapers were cleared.
                if (showsBackgroundModeSelector(assistant.background, assistant.darkBackground, assistant.lightBackground)) {
                    HorizontalDivider()
                    BackgroundModeSelector(
                        modifier = Modifier.padding(8.dp),
                        mode = assistant.backgroundMode,
                        onModeChange = { onUpdate(assistant.copy(backgroundMode = it)) },
                    )
                }
            }

            if (!assistant.useGradientBackground && (assistant.background != null || assistant.darkBackground != null || assistant.lightBackground != null)) {
                val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_background_opacity))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_background_opacity_desc))
                    }
                ) {
                    Slider(
                        value = backgroundOpacity,
                        onValueChange = {
                            onUpdate(
                                assistant.copy(
                                    backgroundOpacity = it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f
                                )
                            )
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_background_opacity_value,
                            (backgroundOpacity * 100).roundToInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_orchestration_budget))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_orchestration_budget_desc))
                }
            ) {
                OutlinedTextField(
                    value = assistant.orchestrationTokenBudget?.toString() ?: "",
                    onValueChange = { text ->
                        val tokens = if (text.isBlank()) {
                            null
                        } else {
                            text.toLongOrNull()?.takeIf { it > 0 }
                        }
                        onUpdate(
                            assistant.copy(
                                orchestrationTokenBudget = tokens
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_orchestration_budget_no_limit))
                    },
                    supportingText = {
                        val budget = assistant.orchestrationTokenBudget
                        if (budget != null) {
                            Text(
                                stringResource(
                                    R.string.assistant_page_orchestration_budget_limit,
                                    budget.toString()
                                )
                            )
                        } else {
                            Text(stringResource(R.string.assistant_page_orchestration_budget_unlimited))
                        }
                    }
                )
            }

            HorizontalDivider()

            var maxSubAgentsInput by remember(
                assistant.id,
                assistant.maxConcurrentSubAgents
            ) {
                mutableStateOf(assistant.maxConcurrentSubAgents.toString())
            }
            var maxSubAgentsFocused by remember(assistant.id) {
                mutableStateOf(false)
            }
            val subAgentFocusManager = LocalFocusManager.current

            fun commitMaxSubAgents() {
                val value = maxSubAgentsInput.toIntOrNull()
                if (value == null) {
                    maxSubAgentsInput = assistant.maxConcurrentSubAgents.toString()
                    return
                }
                val clamped = value.coerceIn(
                    SubAgentDefaults.MIN_PER_ASSISTANT_CAP,
                    SubAgentDefaults.MAX_PER_ASSISTANT_CAP,
                )
                maxSubAgentsInput = clamped.toString()
                if (clamped != assistant.maxConcurrentSubAgents) {
                    onUpdate(assistant.copy(maxConcurrentSubAgents = clamped))
                }
            }

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_max_concurrent_subagents))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_max_concurrent_subagents_desc))
                }
            ) {
                OutlinedTextField(
                    value = maxSubAgentsInput,
                    onValueChange = { input ->
                        if (input.all(Char::isDigit) &&
                            (input.isEmpty() || input.toIntOrNull() != null)
                        ) {
                            maxSubAgentsInput = input
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (maxSubAgentsFocused && !focusState.isFocused) {
                                commitMaxSubAgents()
                            }
                            maxSubAgentsFocused = focusState.isFocused
                        },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { subAgentFocusManager.clearFocus() }
                    ),
                    singleLine = true,
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.assistant_page_max_concurrent_subagents_hint,
                                SubAgentDefaults.MIN_PER_ASSISTANT_CAP,
                                SubAgentDefaults.MAX_PER_ASSISTANT_CAP,
                            )
                        )
                    }
                )
            }

            HorizontalDivider()

            // The assistant-level sub-agent defaults. Both fields existed in the Assistant model
            // (and were already persisted) but nothing ever read them — wiring them here is what
            // makes "sub-agents on a cheaper model" a real setting instead of a dead column.
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_sub_agent_model))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_sub_agent_model_desc))
                },
                content = {
                    ModelSelector(
                        modelId = assistant.subAgentModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = {
                            onUpdate(
                                assistant.copy(
                                    subAgentModelId = it.id
                                )
                            )
                        },
                    )
                }
            )

            HorizontalDivider()

            var subAgentPromptInput by remember(assistant.id, assistant.subAgentSystemPrompt) {
                mutableStateOf(assistant.subAgentSystemPrompt)
            }
            var subAgentPromptFocused by remember(assistant.id) { mutableStateOf(false) }
            val subAgentPromptFocusManager = LocalFocusManager.current
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_sub_agent_system_prompt))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_sub_agent_system_prompt_desc))
                }
            ) {
                OutlinedTextField(
                    value = subAgentPromptInput,
                    onValueChange = { subAgentPromptInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (subAgentPromptFocused && !focusState.isFocused &&
                                subAgentPromptInput != assistant.subAgentSystemPrompt
                            ) {
                                onUpdate(assistant.copy(subAgentSystemPrompt = subAgentPromptInput))
                            }
                            subAgentPromptFocused = focusState.isFocused
                        },
                    minLines = 3,
                )
            }
        }
    }
}
