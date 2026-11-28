// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/BulkSelectBar.tsx, server/thread-title-generate.ts
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE).
// Modified: ported to Kotlin and changed for CLmA.

package com.clma.app.ui.pages.bot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.model.Conversation
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

@Composable
internal fun BotBatchRenameButton(
    conversations: List<Conversation>,
    localModelAvailable: Boolean,
    rename: suspend (List<Uuid>, (Int) -> Unit) -> Set<Uuid>,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Text(stringResource(R.string.bot_batch_select))
    }
    if (open) {
        BotBatchRenameDialog(conversations, localModelAvailable, rename) { open = false }
    }
}

@Composable
private fun BotBatchRenameDialog(
    conversations: List<Conversation>,
    localModelAvailable: Boolean,
    rename: suspend (List<Uuid>, (Int) -> Unit) -> Set<Uuid>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(emptySet<Uuid>()) }
    var running by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val selectedIds = conversations.map { it.id }.filter { it in selected }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.bot_batch_rename)) },
        text = {
            Column {
                if (!localModelAvailable) {
                    Text(stringResource(R.string.bot_batch_local_model))
                }
                result?.let { (renamed, failed) ->
                    Text(stringResource(R.string.bot_batch_result, renamed, failed))
                }
                if (running) {
                    Text(stringResource(R.string.bot_batch_progress, completed, total))
                    LinearProgressIndicator(progress = { completed.toFloat() / total.coerceAtLeast(1) })
                } else {
                    TextButton(onClick = { selected = conversations.map { it.id }.toSet() }) {
                        Text(stringResource(R.string.bot_batch_select_all))
                    }
                    Text(stringResource(R.string.bot_batch_selected, selectedIds.size))
                    LazyColumn(Modifier.heightIn(max = 330.dp)) {
                        items(conversations, key = { it.id.toString() }) { conversation ->
                            val id = conversation.id
                            val checked = id in selected
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .toggleable(checked, role = Role.Checkbox) { enabled ->
                                        selected = if (enabled) selected + id else selected - id
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(
                                    text = conversation.title.ifBlank {
                                        stringResource(R.string.bot_info_untitled_conversation)
                                    },
                                    modifier = Modifier.padding(start = 12.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = localModelAvailable && !running && selectedIds.isNotEmpty(),
                onClick = {
                    total = selectedIds.size
                    completed = 0
                    result = null
                    running = true
                    scope.launch {
                        try {
                            val failed = rename(selectedIds) { completed = it }
                            result = (total - failed.size) to failed.size
                            selected = failed
                        } finally {
                            running = false
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.bot_batch_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) {
                Text(stringResource(R.string.bot_history_close))
            }
        },
    )
}
