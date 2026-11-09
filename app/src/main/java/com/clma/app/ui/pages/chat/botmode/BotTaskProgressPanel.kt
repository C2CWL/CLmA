package com.clma.app.ui.pages.chat.botmode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.botmode.BotTaskStep
import com.clma.app.data.botmode.BotTaskStepStatus
import kotlinx.coroutines.launch

/**
 * Phone-side stand-in for OpenMausBot's cloud computer: a task progress panel wired to real
 * tool / sub-agent steps. Pause stops the current generation; take-over stops it and closes
 * so the user can send the next message themselves.
 */
@Composable
fun BotTaskProgressSheet(
    visible: Boolean,
    botName: String,
    steps: List<BotTaskStep>,
    paused: Boolean,
    isGenerating: Boolean,
    onDismiss: () -> Unit,
    onPauseToggle: () -> Unit,
    onTakeOver: () -> Unit,
) {
    if (!visible) return
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bot_mode_task_title, botName),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(
                    when {
                        paused -> R.string.bot_mode_task_paused_note
                        isGenerating -> R.string.bot_mode_task_live_note
                        else -> R.string.bot_mode_task_idle_note
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val doneOrFailed = steps.count {
                it.status == BotTaskStepStatus.DONE || it.status == BotTaskStepStatus.FAILED
            }
            val progress = if (steps.isEmpty()) {
                0f
            } else {
                doneOrFailed.toFloat() / steps.size.toFloat()
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(steps, key = { it.id }) { step ->
                    val status = when (step.status) {
                        BotTaskStepStatus.DONE -> stringResource(R.string.bot_mode_task_step_done)
                        BotTaskStepStatus.ACTIVE -> stringResource(R.string.bot_mode_task_step_active)
                        BotTaskStepStatus.PENDING -> stringResource(R.string.bot_mode_task_step_pending)
                        BotTaskStepStatus.FAILED -> stringResource(R.string.bot_mode_task_step_failed)
                    }
                    val statusColor = when (step.status) {
                        BotTaskStepStatus.FAILED -> MaterialTheme.colorScheme.error
                        BotTaskStepStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            step.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            status,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onPauseToggle,
                    enabled = isGenerating || paused,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (paused) R.string.bot_mode_task_resume else R.string.bot_mode_task_pause
                        )
                    )
                }
                FilledTonalButton(
                    onClick = onTakeOver,
                    enabled = isGenerating || paused,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.bot_mode_task_takeover))
                }
            }
            TextButton(
                onClick = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.bot_mode_task_close))
            }
        }
    }
}
