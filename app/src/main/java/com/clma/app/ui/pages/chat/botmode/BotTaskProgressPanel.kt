package com.clma.app.ui.pages.chat.botmode

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.ai.tools.local.VirtualDisplayController
import com.clma.app.data.botmode.BotTaskStep
import com.clma.app.data.botmode.BotTaskStepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone-side stand-in for OpenMausBot's cloud computer: task steps + the existing virtual-display
 * live preview. Take-over stops generation and shows [VirtualDisplayPreviewWindow] when a display
 * is already running; otherwise the panel keeps the step list and offers create / manage.
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
    onOpenVirtualDisplayManage: (() -> Unit)? = null,
) {
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val displays by VirtualDisplayController.activeDisplays.collectAsStateWithLifecycle()
    var vdBusy by remember { mutableStateOf(false) }
    var vdError by remember { mutableStateOf<String?>(null) }

    fun showPreviewFor(displayId: Int) {
        if (!Settings.canDrawOverlays(context)) {
            vdError = context.getString(R.string.bot_mode_task_vd_overlay_required)
            return
        }
        vdError = null
        VirtualDisplayController.showPreview(context, displayId)
    }

    fun takeOverInternal() {
        val first = displays.firstOrNull()
        if (first != null) {
            showPreviewFor(first.displayId)
        }
        onTakeOver()
    }

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

            Text(
                text = stringResource(R.string.bot_mode_task_vd_section),
                style = MaterialTheme.typography.titleSmall,
            )
            if (displays.isEmpty()) {
                Text(
                    text = stringResource(R.string.bot_mode_task_vd_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (vdBusy) return@FilledTonalButton
                            vdBusy = true
                            vdError = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    VirtualDisplayController.create(
                                        context.applicationContext,
                                        width = 720,
                                        height = 1280,
                                        densityDpi = 320,
                                        showPreview = true,
                                    )
                                }
                                vdBusy = false
                                when (result) {
                                    is VirtualDisplayController.Result.Ok -> {
                                        if (!Settings.canDrawOverlays(context)) {
                                            vdError = context.getString(
                                                R.string.bot_mode_task_vd_overlay_required,
                                            )
                                        }
                                    }
                                    is VirtualDisplayController.Result.Error -> {
                                        vdError = result.message
                                    }
                                }
                            }
                        },
                        enabled = !vdBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.bot_mode_task_vd_create))
                    }
                    if (onOpenVirtualDisplayManage != null) {
                        OutlinedButton(
                            onClick = onOpenVirtualDisplayManage,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.bot_mode_task_vd_manage))
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.bot_mode_task_vd_active, displays.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                displays.forEach { display ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.bot_mode_task_vd_item,
                                display.displayId,
                                display.width,
                                display.height,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showPreviewFor(display.displayId) }) {
                            Text(stringResource(R.string.bot_mode_task_vd_preview))
                        }
                    }
                }
            }
            vdError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 220.dp),
            ) {
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
                    onClick = { takeOverInternal() },
                    enabled = isGenerating || paused || displays.isNotEmpty(),
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
