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
import kotlinx.coroutines.launch

/**
 * Phone-side stand-in for OpenMausBot's cloud computer: a task progress panel.
 * UI shell only — steps are stubbed until real tool progress is wired (TODO).
 */
data class BotTaskStep(
    val id: String,
    val title: String,
    val done: Boolean = false,
    val active: Boolean = false,
)

@Composable
fun BotTaskProgressSheet(
    visible: Boolean,
    botName: String,
    steps: List<BotTaskStep>,
    paused: Boolean,
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
                text = stringResource(R.string.bot_mode_task_stub_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val progress = if (steps.isEmpty()) {
                0f
            } else {
                steps.count { it.done }.toFloat() / steps.size.toFloat()
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(steps, key = { it.id }) { step ->
                    val status = when {
                        step.done -> stringResource(R.string.bot_mode_task_step_done)
                        step.active -> stringResource(R.string.bot_mode_task_step_active)
                        else -> stringResource(R.string.bot_mode_task_step_pending)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(step.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onPauseToggle, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (paused) R.string.bot_mode_task_resume else R.string.bot_mode_task_pause
                        )
                    )
                }
                FilledTonalButton(onClick = onTakeOver, modifier = Modifier.weight(1f)) {
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

@Composable
fun rememberLocalizedStubSteps(): List<BotTaskStep> = listOf(
    BotTaskStep("1", stringResource(R.string.bot_mode_task_stub_prepare), done = true),
    BotTaskStep("2", stringResource(R.string.bot_mode_task_stub_run), active = true),
    BotTaskStep("3", stringResource(R.string.bot_mode_task_stub_summarize)),
)
