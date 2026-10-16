package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.ApprovalMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.subagent.SubAgentDispatchProfile
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private val levels = ReasoningLevel.entries
private val levelCount = levels.size

/**
 * The chat model sheet's control bubble (拍板 2026-10-16): approval mode and sub-agent dispatch
 * posture chips on the left, the thinking-depth word (the only per-level-coloured element) on
 * the right, and the surge depth slider underneath:
 *
 *     ╭──────────────────────────────────────╮
 *     │ ( Auto ) ( 全派发 )            极致  │
 *     │ ──────────────slider──────────────── │
 *     ╰──────────────────────────────────────╯
 *
 * The two chips are compact mirrors of Settings → Tool approvals / Settings → Sub-agents; the
 * settings pages stay the canonical detail surfaces (blacklist, caps readout). The slider only
 * renders when the current model can reason; the chips always do.
 */
@Composable
fun AgentControlCard(
    reasoningLevel: ReasoningLevel,
    showReasoning: Boolean,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val approvalPrefs = koinInject<ToolApprovalPreferences>()
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    val approvalMode by approvalPrefs.modeFlow.collectAsStateWithLifecycle(initialValue = ApprovalMode.MANUAL)
    val liveSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val dispatchProfile = liveSettings.subAgentDispatchProfile

    // Same gate as Settings → Tool approvals: switching INTO Auto confirms first.
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

    // The depth word + surge track follow the thumb while dragging, not just after release.
    val currentIndex = levels.indexOf(reasoningLevel).coerceAtLeast(0)
    var sliderValue by remember { mutableFloatStateOf(currentIndex.toFloat()) }
    LaunchedEffect(currentIndex) {
        sliderValue = currentIndex.toFloat()
    }
    val previewLevel = levels[sliderValue.roundToInt().coerceIn(0, levelCount - 1)]

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ApprovalModeChip(
                    mode = approvalMode,
                    onSelect = { mode ->
                        if (mode == ApprovalMode.AUTO && approvalMode != ApprovalMode.AUTO) {
                            pendingAuto = true
                        } else {
                            scope.launch { approvalPrefs.setMode(mode) }
                        }
                    },
                )
                DispatchProfileChip(
                    profile = dispatchProfile,
                    onSelect = { profile ->
                        scope.launch { settingsStore.update { it.copy(subAgentDispatchProfile = profile) } }
                    },
                )
                Spacer(Modifier.weight(1f))
                if (showReasoning) {
                    ReasoningIcon(previewLevel, Modifier.size(18.dp))
                    ReasoningLevelWord(
                        level = previewLevel,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
            if (showReasoning) {
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        val snappedIndex = sliderValue.roundToInt().coerceIn(0, levelCount - 1)
                        sliderValue = snappedIndex.toFloat()
                        onUpdateReasoningLevel(levels[snappedIndex])
                    },
                    valueRange = 0f..(levelCount - 1).toFloat(),
                    steps = levelCount - 2,
                    modifier = Modifier.fillMaxWidth(),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onPrimary)
                            )
                        }
                    },
                    track = { sliderState ->
                        ReasoningSurgeTrack(sliderState, previewLevel)
                    },
                )
            }
        }
    }
}

/** A compact pill showing the current value; tapping opens the picker menu under it. */
@Composable
private fun ControlChip(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Icon(
                imageVector = HugeIcons.ArrowDown01,
                contentDescription = contentDescription,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ApprovalModeChip(
    mode: ApprovalMode,
    onSelect: (ApprovalMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ControlChip(
            label = approvalModeLabel(mode),
            contentDescription = stringResource(R.string.setting_page_tool_approvals_mode_title),
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ApprovalMode.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(approvalModeLabel(entry)) },
                    leadingIcon = {
                        if (entry == mode) {
                            Icon(HugeIcons.Tick02, contentDescription = null, modifier = Modifier.size(16.dp))
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
private fun DispatchProfileChip(
    profile: SubAgentDispatchProfile,
    onSelect: (SubAgentDispatchProfile) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ControlChip(
            label = dispatchProfileLabel(profile),
            contentDescription = stringResource(R.string.setting_sub_agents_dispatch_profile_title),
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SubAgentDispatchProfile.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(dispatchProfileLabel(entry)) },
                    leadingIcon = {
                        if (entry == profile) {
                            Icon(HugeIcons.Tick02, contentDescription = null, modifier = Modifier.size(16.dp))
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
