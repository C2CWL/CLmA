// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), docs/team-board.md, src/lib/room-work.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.botmode.board.BoardStatus
import com.clma.app.data.botmode.board.BoardTask
import com.clma.app.data.botmode.board.BotBoard
import com.clma.app.data.botmode.board.BotBoardStore
import com.clma.app.data.botmode.board.findBoardTask
import com.clma.app.data.model.Assistant
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
internal fun BotBoardTab(bots: List<Assistant>, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { BotBoardStore(context) }
    val defaultBotName = stringResource(R.string.assistant_page_default_assistant)
    val roster = bots.associate { it.id.toString() to it.name.ifBlank { defaultBotName } }
    val board = BotBoard(roster.keys)
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<BoardTask>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableStateOf(0) }
    var saveFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<BoardStatus?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(store, loadAttempt) {
        store.tasks.catch { error ->
            if (error !is IOException && error !is IllegalArgumentException) throw error
            loadFailed = true
        }.collect { tasks = it }
    }

    fun save(transform: (List<BoardTask>) -> BoardTask, onSuccess: () -> Unit = {}) {
        if (saving) return
        saving = true
        saveFailed = false
        scope.launch {
            try {
                store.mutate(transform)
                onSuccess()
            } catch (_: IOException) {
                saveFailed = true
            } catch (_: IllegalArgumentException) {
                saveFailed = true
            } finally {
                saving = false
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(enabled = tasks != null && !loadFailed, onClick = {
                saveFailed = false
                creating = true
            }) { Text(stringResource(R.string.bot_board_new)) }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter == null,
                onClick = { filter = null },
                label = { Text(stringResource(R.string.bot_board_all)) },
            )
            for (status in BoardStatus.entries) {
                FilterChip(
                    selected = filter == status,
                    onClick = { filter = status },
                    label = { Text(stringResource(status.label)) },
                )
            }
        }

        val visible = tasks.orEmpty().filter { filter == null || it.status == filter }
        when {
            loadFailed -> TextButton(onClick = {
                loadFailed = false
                loadAttempt++
            }, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.bot_board_load_retry)) }
            tasks == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            visible.isEmpty() -> Text(stringResource(R.string.bot_board_empty), modifier = Modifier.padding(24.dp))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(visible, key = { it.id }) { task ->
                    ListItem(
                        headlineContent = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(stringResource(R.string.bot_board_row, stringResource(task.status.label),
                                ownerName(task.ownerBotId, roster)))
                        },
                        modifier = Modifier.clickable {
                            saveFailed = false
                            selectedId = task.id
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    if (creating && tasks != null && !loadFailed) {
        NewTaskDialog(
            saving = saving,
            saveFailed = saveFailed,
            onDismiss = { creating = false },
            onCreate = { title, description ->
                save({ board.create(it, null, title, description) }) {
                    creating = false
                    filter = null
                }
            },
        )
    }

    tasks?.firstOrNull { it.id == selectedId }?.let { task ->
        TaskDialog(
            task = task,
            roster = roster,
            saving = saving,
            saveFailed = saveFailed,
            onDismiss = { selectedId = null },
            onAssign = { target ->
                save({ board.assign(it.findBoardTask(task.id), null, target) })
            },
            onMove = { status ->
                save({ board.update(it, it.findBoardTask(task.id), null, status, null, null) })
            },
            onComment = { text, onSuccess ->
                save({ board.comment(it.findBoardTask(task.id), null, text) }, onSuccess)
            },
        )
    }
}

@Composable
private fun NewTaskDialog(
    saving: Boolean,
    saveFailed: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.bot_board_new)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(BotBoard.MAX_TITLE) },
                    label = { Text(stringResource(R.string.bot_board_title)) },
                    enabled = !saving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(BotBoard.MAX_TEXT) },
                    label = { Text(stringResource(R.string.bot_board_description)) },
                    enabled = !saving,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (saveFailed) BoardSaveError()
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && !saving, onClick = { onCreate(title, description) }) {
                Text(stringResource(R.string.bot_board_add))
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.bot_board_cancel)) }
        },
    )
}

@Composable
private fun TaskDialog(
    task: BoardTask,
    roster: Map<String, String>,
    saving: Boolean,
    saveFailed: Boolean,
    onDismiss: () -> Unit,
    onAssign: (String?) -> Unit,
    onMove: (BoardStatus) -> Unit,
    onComment: (String, () -> Unit) -> Unit,
) {
    var comment by rememberSaveable(task.id) { mutableStateOf("") }
    val hasOwner = task.ownerBotId in roster
    val statuses = BoardStatus.entries.filter {
        hasOwner || it == task.status || it == BoardStatus.TODO || it == BoardStatus.DONE
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(task.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.bot_board_created_by, authorName(task.createdByBotId, roster)),
                    style = MaterialTheme.typography.bodySmall)
                if (task.description.isNotEmpty()) Text(task.description)
                BoardPicker(
                    label = stringResource(R.string.bot_board_status, stringResource(task.status.label)),
                    options = statuses.map { it to stringResource(it.label) },
                    enabled = !saving,
                    onSelect = { if (it != task.status) onMove(it) },
                )
                BoardPicker(
                    label = stringResource(R.string.bot_board_owner, ownerName(task.ownerBotId, roster)),
                    options = listOf(null to stringResource(R.string.bot_board_unassigned)) + roster.toList(),
                    enabled = !saving && task.status != BoardStatus.DONE,
                    onSelect = { if (it != task.ownerBotId) onAssign(it) },
                )
                for (entry in task.comments) {
                    HorizontalDivider()
                    Text(authorName(entry.botId, roster), style = MaterialTheme.typography.labelMedium)
                    Text(entry.text)
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(BotBoard.MAX_TEXT) },
                    label = { Text(stringResource(R.string.bot_board_comment)) },
                    enabled = !saving,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(enabled = comment.isNotBlank() && !saving, onClick = {
                    onComment(comment) { comment = "" }
                }) { Text(stringResource(R.string.bot_board_add_comment)) }
                if (saveFailed) BoardSaveError()
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.bot_board_close)) }
        },
    )
}

@Composable
private fun <T> BoardPicker(label: String, options: List<Pair<T, String>>, enabled: Boolean, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled, onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((value, name) in options) {
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    expanded = false
                    onSelect(value)
                })
            }
        }
    }
}

@Composable
private fun BoardSaveError() {
    Text(stringResource(R.string.bot_board_save_failed), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ownerName(id: String?, roster: Map<String, String>): String =
    if (id == null) stringResource(R.string.bot_board_unassigned)
    else roster[id] ?: stringResource(R.string.bot_board_missing_bot)

@Composable
private fun authorName(id: String?, roster: Map<String, String>): String =
    if (id == null) stringResource(R.string.bot_board_user)
    else roster[id] ?: stringResource(R.string.bot_board_missing_bot)

private val BoardStatus.label: Int
    get() = when (this) {
        BoardStatus.TODO -> R.string.bot_board_todo
        BoardStatus.IN_PROGRESS -> R.string.bot_board_in_progress
        BoardStatus.REVIEW -> R.string.bot_board_review
        BoardStatus.DONE -> R.string.bot_board_done
    }
