package com.clma.app.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.sync.BackupManager
import com.clma.app.data.sync.StorageUsage
import com.clma.app.ui.pages.backup.components.backupItemLabelRes
import com.clma.app.utils.fileSizeToString
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

/** No backup selections, uploads or delete actions on this surface. */
@Composable
internal fun StorageUsageTab() {
    val manager = koinInject<BackupManager>()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        loading = true
        failed = false
        try {
            usage = manager.estimateStorageUsage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Column {
                Text(stringResource(R.string.storage_usage_scope), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { refresh++ }, enabled = !loading) {
                    Text(stringResource(R.string.storage_usage_refresh))
                }
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.storage_usage_failed))
            }
        }
        usage?.let { sizes ->
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.storage_usage_total)) },
                    trailingContent = { Text(sizes.total.fileSizeToString()) },
                )
            }
            items(sizes.items.entries.toList(), key = { it.key.name }) { (category, bytes) ->
                ListItem(
                    headlineContent = { Text(stringResource(backupItemLabelRes(category))) },
                    trailingContent = { Text(bytes.fileSizeToString()) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.storage_usage_settings)) },
                    trailingContent = { Text(sizes.settingsBytes.fileSizeToString()) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.storage_usage_cache)) },
                    trailingContent = { Text(sizes.cacheBytes.fileSizeToString()) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.local_snapshot_title)) },
                    trailingContent = { Text(sizes.snapshotBytes.fileSizeToString()) },
                )
            }
        }
    }
}
