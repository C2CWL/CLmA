package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agentdef.LocalToolGroups
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.pages.setting.localToolTitleRes
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
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
        AssistantLocalToolContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            // Transform-based path for every control on this page. Each tap runs inside
            // SettingsStore.update's mutex against the genuinely-current Assistant, so rapid taps
            // all serialise against the actual current state instead of whatever stale snapshot
            // the recomposition was holding.
            onUpdateAssistant = { transform -> vm.updateAssistant(transform) },
        )
    }
}

/**
 * Two levels, joined: **groups**, and the tools inside a group.
 *
 * The group switch decides whether a group's schemas reach the model at all; opening an enabled
 * group reveals its tools, each with its own switch, so one tool inside a group you are keeping can
 * be dropped without losing the rest. The two levels used to be listed apart — a switch per group,
 * then a flat list of every tool name below them with no indication of which group a name came
 * from — which made the second list impossible to use for anything but a whole-surface sweep.
 *
 * Tool names come from the live [LocalTools] factory rather than a typed-in list, so a row here
 * cannot drift from the surface the model actually receives.
 */
@Composable
private fun AssistantLocalToolContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
) {
    val ctx = LocalContext.current
    val localToolFactory = koinInject<LocalTools>()

    // Group titles resolved once, outside any non-composable predicate, so the search filter can
    // match a group by its human name (which is how a Chinese-language user looks for "电池").
    val groupTitles: Map<LocalToolOption, String> = remember(ctx) {
        LocalToolGroups.all.associateWith { ctx.getString(localToolTitleRes(it)) }
    }

    // Computed once per page visit: the inventory does not depend on anything the user changes
    // here, and the factory is the only authority on what a group actually produces.
    val groupTools: Map<LocalToolOption, List<String>> = remember(localToolFactory) {
        LocalToolGroups.all.associateWith { option ->
            localToolFactory.getTools(listOf(option))
                .map { it.name }
                .filterNot { it in LocalTools.HIDDEN_TOOL_NAMES }
        }
    }

    val enabledGroups: Set<LocalToolOption> = remember(assistant.localTools) {
        assistant.localTools.toSet()
    }
    val disabledTools: Set<String> = assistant.disabledLocalTools

    // Hardware-availability gate for the NFC group: a device with no NFC chip can never run those
    // tools, so the switch is shown disabled rather than letting the user enable a group that would
    // only ever error.
    val hasNfc = remember {
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
    }

    var query by remember { mutableStateOf("") }
    var openedGroups by remember { mutableStateOf(emptySet<LocalToolOption>()) }

    fun setGroupEnabled(option: LocalToolOption, enabled: Boolean) {
        onUpdateAssistant { current ->
            current.copy(
                localTools = if (enabled) {
                    (current.localTools + option).distinct()
                } else {
                    current.localTools - option
                },
            )
        }
    }

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        onUpdateAssistant { current ->
            current.copy(
                disabledLocalTools = if (enabled) {
                    current.disabledLocalTools - toolName
                } else {
                    current.disabledLocalTools + toolName
                },
            )
        }
    }

    fun toggleOpen(option: LocalToolOption) {
        openedGroups = if (option in openedGroups) openedGroups - option else openedGroups + option
    }

    val needle = query.trim()
    val searching = needle.isNotEmpty()
    val visibleGroups: List<LocalToolOption> = remember(needle, groupTitles, groupTools) {
        if (!searching) {
            LocalToolGroups.all
        } else {
            LocalToolGroups.all.filter { option ->
                groupTitles[option].orEmpty().contains(needle, ignoreCase = true) ||
                    groupTools[option].orEmpty().any { it.contains(needle, ignoreCase = true) }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.assistant_page_local_tools_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.assistant_page_local_tools_search_hint)) },
            shape = RoundedCornerShape(50),
            singleLine = true,
        )

        if (visibleGroups.isEmpty()) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_no_match),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        } else CardGroup {
            visibleGroups.forEach { option ->
                val enabled = option in enabledGroups
                val available = option != LocalToolOption.Nfc || hasNfc
                val tools = groupTools[option].orEmpty()
                val offCount = tools.count { it in disabledTools }
                // A search opens what it found; otherwise the user's own taps decide.
                val opened = enabled && (searching || option in openedGroups)

                item(
                    onClick = { if (enabled) toggleOpen(option) },
                    headlineContent = { Text(groupTitles[option].orEmpty()) },
                    supportingContent = {
                        Text(
                            text = if (!available) {
                                stringResource(R.string.assistant_page_local_tools_nfc_unavailable)
                            } else if (offCount > 0) {
                                stringResource(
                                    R.string.assistant_page_local_tools_group_count_off,
                                    tools.size,
                                    offCount,
                                )
                            } else {
                                stringResource(R.string.assistant_page_local_tools_group_count, tools.size)
                            }
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (enabled) {
                                IconButton(onClick = { toggleOpen(option) }) {
                                    Icon(
                                        imageVector = if (opened) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                        contentDescription = stringResource(
                                            if (opened) {
                                                R.string.assistant_page_local_tools_collapse
                                            } else {
                                                R.string.assistant_page_local_tools_expand
                                            }
                                        ),
                                    )
                                }
                            }
                            Switch(
                                checked = enabled && available,
                                enabled = available,
                                onCheckedChange = { setGroupEnabled(option, it) },
                            )
                        }
                    },
                )

                if (opened) {
                    tools.forEach { toolName ->
                        item(
                            modifier = Modifier.padding(start = 24.dp),
                            headlineContent = {
                                Text(
                                    text = toolName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = stringResource(
                                        if (toolName in disabledTools) {
                                            R.string.assistant_page_local_tools_tool_off
                                        } else {
                                            R.string.assistant_page_local_tools_tool_on
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = toolName !in disabledTools,
                                    onCheckedChange = { setToolEnabled(toolName, it) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
