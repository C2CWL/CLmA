package com.clma.app.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.sync.LocalSnapshot
import com.clma.app.data.sync.LocalSnapshotConfig
import com.clma.app.data.sync.LocalSnapshotManager
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.utils.fileSizeToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LocalSnapshotsTab(onShowRestartDialog: () -> Unit) {
    val manager = koinInject<LocalSnapshotManager>()
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<LocalSnapshotConfig?>(null) }
    var snapshots by remember { mutableStateOf(emptyList<LocalSnapshot>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LocalSnapshot?>(null) }

    suspend fun reload() {
        config = manager.config()
        snapshots = manager.list()
    }

    fun update(transform: (LocalSnapshotConfig) -> LocalSnapshotConfig) {
        busy = true
        scope.launch {
            try {
                manager.updateConfig(transform)
                reload()
                error = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = true
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(manager) {
        try {
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.local_snapshot_description))
        Text(stringResource(R.string.local_snapshot_privacy))
        config?.let { current ->
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.local_snapshot_enable)) },
                    trailingContent = {
                        Switch(
                            checked = current.enabled,
                            enabled = !busy,
                            onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.local_snapshot_interval)) },
                    supportingContent = {
                        SnapshotChoices(listOf(12, 24, 72, 168), current.intervalHours, !busy) { hours ->
                            update { it.copy(intervalHours = hours) }
                        }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.local_snapshot_keep)) },
                    supportingContent = {
                        SnapshotChoices(listOf(1, 3, 5, 10), current.keepCount, !busy) { count ->
                            update { it.copy(keepCount = count) }
                        }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.local_snapshot_limit)) },
                    supportingContent = {
                        SnapshotChoices(listOf(128, 256, 512, 1024), current.maxMiB, !busy) { limit ->
                            update { it.copy(maxMiB = limit) }
                        }
                    },
                )
            }
            if (current.lastSuccess > 0) {
                Text(stringResource(R.string.local_snapshot_last_success, snapshotDate(current.lastSuccess)))
            }
            if (current.failures > 0) Text(stringResource(R.string.local_snapshot_retry))
        }
        Text(stringResource(R.string.local_snapshot_storage))
        if (busy) CircularProgressIndicator()
        if (error) Text(stringResource(R.string.local_snapshot_error))
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        reload()
                        error = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        error = true
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(stringResource(R.string.local_snapshot_refresh)) }
        if (snapshots.isEmpty()) Text(stringResource(R.string.local_snapshot_empty))
        snapshots.forEach { snapshot ->
            CardGroup {
                item(
                    onClick = if (busy) null else ({ selected = snapshot }),
                    headlineContent = {
                        Text(stringResource(
                            if (snapshot.rollback) R.string.local_snapshot_rollback else R.string.local_snapshot_automatic,
                            snapshotDate(snapshot.createdAt),
                        ))
                    },
                    supportingContent = { Text(snapshot.sizeBytes.fileSizeToString()) },
                    trailingContent = { Text(stringResource(R.string.backup_page_restore)) },
                )
            }
        }
    }
    selected?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(stringResource(R.string.backup_page_restore)) },
            text = { Text(stringResource(R.string.local_snapshot_restore_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    selected = null
                    busy = true
                    scope.launch {
                        try {
                            manager.restore(snapshot)
                            error = false
                            onShowRestartDialog()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = true
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SnapshotChoices(options: List<Int>, value: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == value,
                onClick = { onSelect(option) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(option.toString()) }
        }
    }
}

private fun snapshotDate(time: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
