package com.clma.app.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.sync.BackupItem
import com.clma.app.data.sync.BackupSizeEstimator
import com.clma.app.utils.fileSizeToString

/**
 * Multi-select chip list of the backup slices. Replaces the two-button segmented row now that the
 * selection has grown past what a single row can hold (and wraps on narrow screens).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupItemPicker(
    selected: Collection<BackupItem>,
    onToggle: (BackupItem) -> Unit,
    modifier: Modifier = Modifier,
    sizes: Map<BackupItem, Long>? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BackupItem.selectable.forEach { item ->
                val label = stringResource(backupItemLabelRes(item))
                val size = sizes?.get(item)
                FilterChip(
                    selected = item in selected,
                    onClick = { onToggle(item) },
                    label = { Text(if (size != null) "$label · ${size.fileSizeToString()}" else label) },
                )
            }
        }
        if (sizes != null) {
            Text(
                text = stringResource(
                    R.string.backup_page_estimated_total_size,
                    BackupSizeEstimator.totalFor(sizes, selected).fileSizeToString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun backupItemLabelRes(item: BackupItem): Int = when (item) {
    BackupItem.DATABASE -> R.string.backup_page_chat_records
    BackupItem.FILES -> R.string.backup_page_files
    BackupItem.SKILLS -> R.string.backup_item_skills
    BackupItem.UPLOAD -> R.string.backup_item_upload
    BackupItem.IMAGES -> R.string.backup_item_images
    BackupItem.VIDEOS -> R.string.backup_item_videos
    BackupItem.FONTS -> R.string.backup_item_fonts
    BackupItem.WORKSPACES -> R.string.backup_item_workspaces
    BackupItem.ASSISTANT_MEMORY -> R.string.backup_item_assistant_memory
    BackupItem.TOOL_OUTPUTS -> R.string.backup_item_tool_outputs
    BackupItem.CONFIG -> R.string.backup_item_config
    BackupItem.SECRETS -> R.string.backup_item_secrets
}
