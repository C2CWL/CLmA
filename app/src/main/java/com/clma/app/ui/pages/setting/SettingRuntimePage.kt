package com.clma.app.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.datastore.AutoCompactionThresholdMode
import com.clma.app.data.datastore.Settings
import com.clma.app.ui.pages.setting.termux.SettingTermuxViewModel
import com.clma.app.ui.pages.setting.termux.TimeoutInput
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.SettingSlider
import com.clma.app.ui.components.ui.parseIntInRange
import com.clma.app.utils.plus
import org.koin.androidx.compose.koinViewModel


/**
 * 运行与上下文: how one turn runs — wall-clock budget, tool-step ceiling, parsing tool calls out of
 * reply text — and automatic context compaction. Everything here moved from 模型设置 and
 * 交互行为 without changing where it is stored: the two limits stay in TermuxPreferences (through
 * [SettingTermuxViewModel]), the rest in [Settings].
 */
@Composable
fun SettingRuntimePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    SettingSubPageScaffold(title = stringResource(R.string.setting_hub_runtime)) {
        item {
            GenerationLimitsSettingItem(settings = settings, vm = vm)
        }
        item {
            AutoCompactionSettingItem(settings = settings, vm = vm)
        }
    }
}

/**
 * Per-turn generation limits — the wall-clock budget and the tool-step ceiling for one
 * user turn. These are app-wide (they govern every tool family and the model loop itself,
 * see [com.clma.app.data.ai.limits.ToolRuntimeLimits]); they live on 运行与上下文 (they used to be
 * on the model page) because they shape generation behaviour, not Termux specifically. Persistence stays in
 * TermuxPreferences (the pre-existing store), surfaced here through SettingTermuxViewModel.
 */
@Composable
private fun GenerationLimitsSettingItem(
    settings: Settings,
    vm: SettingVM,
    termuxVm: SettingTermuxViewModel = koinViewModel(),
) {
    val config by termuxVm.config.collectAsStateWithLifecycle()

    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.setting_model_page_generation_limits)) },
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_model_page_turn_budget)) },
            supportingContent = { Text(stringResource(R.string.setting_model_page_turn_budget_desc)) },
            trailingContent = {
                TimeoutInput(
                    currentValue = config.turnBudgetMs / 60_000L,
                    unitLabel = stringResource(R.string.setting_termux_unit_minutes),
                    onCommit = termuxVm::setTurnBudgetMinutes,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_model_page_max_tool_steps)) },
            supportingContent = { Text(stringResource(R.string.setting_model_page_max_tool_steps_desc)) },
            trailingContent = {
                TimeoutInput(
                    currentValue = config.maxToolSteps.toLong(),
                    unitLabel = stringResource(R.string.setting_termux_unit_steps),
                    onCommit = termuxVm::setMaxToolSteps,
                )
            },
        )
        // Moved here from 交互行为 › 高级: it decides whether tool calls written into the reply text
        // are run, so it belongs with the other per-turn limits rather than with chat input.
        item(
            headlineContent = { Text(stringResource(R.string.setting_display_page_parse_text_tool_calls_title)) },
            supportingContent = { Text(stringResource(R.string.setting_display_page_parse_text_tool_calls_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.parseTextToolCalls,
                    onCheckedChange = { enabled ->
                        vm.updateSettings { it.copy(parseTextToolCalls = enabled) }
                    }
                )
            },
        )
    }
}

@Composable
private fun AutoCompactionSettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    var threshold by remember(settings.autoCompactionThresholdPercent) {
        mutableFloatStateOf(settings.autoCompactionThresholdPercent.toFloat())
    }
    var tokenThreshold by remember(settings.autoCompactionThresholdTokensK) {
        mutableStateOf(settings.autoCompactionThresholdTokensK.toString())
    }
    var compactionTargetTokensK by remember(settings.contextCompactionTargetTokensK) {
        mutableStateOf(settings.contextCompactionTargetTokensK?.toString().orEmpty())
    }
    var keepRecentToolCalls by remember(settings.autoCompactionKeepRecentToolCalls) {
        mutableStateOf(settings.autoCompactionKeepRecentToolCalls.toString())
    }

    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.setting_runtime_section_compaction)) },
    ) {
        item(
            headlineContent = {
                Text(stringResource(R.string.setting_model_page_enable_auto_compaction))
            },
            supportingContent = {
                Text(stringResource(R.string.setting_model_page_enable_auto_compaction_desc))
            },
            trailingContent = {
                Switch(
                    checked = settings.enableAutoCompaction,
                    onCheckedChange = {
                        vm.updateSettings { current -> current.copy(enableAutoCompaction = it) }
                    },
                )
            },
        )
        if (settings.enableAutoCompaction) {
            item(
                headlineContent = {
                    Text(stringResource(R.string.setting_model_page_auto_compaction_threshold))
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.setting_model_page_auto_compaction_threshold_desc))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val modes = listOf(
                                AutoCompactionThresholdMode.PERCENT to
                                    stringResource(R.string.setting_model_page_auto_compaction_mode_percent),
                                AutoCompactionThresholdMode.TOKENS to
                                    stringResource(R.string.setting_model_page_auto_compaction_mode_tokens),
                            )
                            modes.forEachIndexed { index, (mode, label) ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                    selected = settings.autoCompactionThresholdMode == mode,
                                    onClick = {
                                        if (settings.autoCompactionThresholdMode != mode) {
                                            vm.updateSettings { current ->
                                                current.copy(autoCompactionThresholdMode = mode)
                                            }
                                        }
                                    },
                                ) {
                                    Text(label)
                                }
                            }
                        }
                        if (settings.autoCompactionThresholdMode == AutoCompactionThresholdMode.PERCENT) {
                            SettingSlider(
                                value = threshold,
                                onValueChange = { value ->
                                    val snapped = ((value / 5f).toInt() * 5).toFloat().coerceIn(5f, 95f)
                                    threshold = snapped
                                    vm.updateSettings { current ->
                                        current.copy(autoCompactionThresholdPercent = snapped.toInt())
                                    }
                                },
                                valueRange = 5f..95f,
                                title = stringResource(R.string.setting_model_page_auto_compaction_threshold),
                                valueLabel = { "${it.toInt()}%" },
                                parseInput = { parseIntInRange(it, 5f..95f) },
                                snap = { raw -> ((raw / 5f).toInt() * 5).toFloat().coerceIn(5f, 95f) },
                                rangeHint = "5% – 95%",
                            )
                        } else {
                            OutlinedTextField(
                                value = tokenThreshold,
                                onValueChange = { value ->
                                    tokenThreshold = value.filter(Char::isDigit).take(7)
                                    tokenThreshold.toIntOrNull()?.let { parsed ->
                                        val normalized = parsed.coerceIn(1, Int.MAX_VALUE / 1_000)
                                        if (normalized != settings.autoCompactionThresholdTokensK) {
                                            vm.updateSettings { current ->
                                                current.copy(autoCompactionThresholdTokensK = normalized)
                                            }
                                        }
                                    }
                                },
                                singleLine = true,
                                suffix = { Text("k") },
                                label = {
                                    Text(stringResource(R.string.setting_model_page_auto_compaction_tokens))
                                },
                                supportingText = {
                                    Text(stringResource(R.string.setting_model_page_auto_compaction_tokens_desc))
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { focusState ->
                                        if (!focusState.isFocused) {
                                            val parsed = tokenThreshold.toIntOrNull()
                                            if (parsed == null) {
                                                tokenThreshold = settings.autoCompactionThresholdTokensK.toString()
                                            } else {
                                                val normalized = parsed.coerceIn(1, Int.MAX_VALUE / 1_000)
                                                tokenThreshold = normalized.toString()
                                                if (normalized != settings.autoCompactionThresholdTokensK) {
                                                    vm.updateSettings { current ->
                                                        current.copy(autoCompactionThresholdTokensK = normalized)
                                                    }
                                                }
                                            }
                                        }
                                    },
                            )
                        }
                    }
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.setting_model_page_auto_compaction_keep_tool_calls))
                },
                supportingContent = {
                    OutlinedTextField(
                        value = keepRecentToolCalls,
                        onValueChange = { value ->
                            keepRecentToolCalls = value.filter(Char::isDigit).take(4)
                            keepRecentToolCalls.toIntOrNull()?.let { parsed ->
                                val normalized = parsed.coerceIn(0, 1_000)
                                if (normalized != settings.autoCompactionKeepRecentToolCalls) {
                                    vm.updateSettings { current ->
                                        current.copy(autoCompactionKeepRecentToolCalls = normalized)
                                    }
                                }
                            }
                        },
                        singleLine = true,
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.setting_model_page_auto_compaction_keep_tool_calls_desc
                                )
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    val normalized = keepRecentToolCalls.toIntOrNull()
                                        ?.coerceIn(0, 1_000)
                                        ?: settings.autoCompactionKeepRecentToolCalls
                                    keepRecentToolCalls = normalized.toString()
                                    if (normalized != settings.autoCompactionKeepRecentToolCalls) {
                                        vm.updateSettings { current ->
                                            current.copy(autoCompactionKeepRecentToolCalls = normalized)
                                        }
                                    }
                                }
                            },
                    )
                },
            )
        }
        item(
            headlineContent = {
                Text(stringResource(R.string.setting_model_page_context_compaction_target))
            },
            supportingContent = {
                OutlinedTextField(
                    value = compactionTargetTokensK,
                    onValueChange = { value ->
                        compactionTargetTokensK = value.filter(Char::isDigit).take(7)
                        val normalized = compactionTargetTokensK.toIntOrNull()
                            ?.coerceIn(1, Int.MAX_VALUE / 1_000)
                        if (normalized != settings.contextCompactionTargetTokensK) {
                            vm.updateSettings { current ->
                                current.copy(contextCompactionTargetTokensK = normalized)
                            }
                        }
                    },
                    singleLine = true,
                    suffix = { Text("k") },
                    supportingText = {
                        Text(stringResource(R.string.setting_model_page_context_compaction_target_desc))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused) {
                                val normalized = compactionTargetTokensK.toIntOrNull()
                                    ?.coerceIn(1, Int.MAX_VALUE / 1_000)
                                val normalizedValue = normalized?.toString().orEmpty()
                                compactionTargetTokensK = normalizedValue
                                if (normalized != settings.contextCompactionTargetTokensK) {
                                    vm.updateSettings { current ->
                                        current.copy(contextCompactionTargetTokensK = normalized)
                                    }
                                }
                            }
                        },
                )
            },
        )
    }
}

