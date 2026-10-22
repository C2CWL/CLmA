package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

@Composable
fun WorkspaceCwdPickerSheet(
    workspaceId: String,
    currentCwd: String?,
    onSelectCwd: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspaceRepository: WorkspaceRepository = koinInject()

    var browsePath by remember { mutableStateOf(fromAbsolutePath(currentCwd)) }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var newFolderError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(browsePath) {
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                workspaceRepository.listFiles(workspaceId, WorkspaceStorageArea.FILES, browsePath)
            }
            entries = result.sortedWith(compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.name })
            loading = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            entries = emptyList()
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_cwd_select_directory),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = stringResource(R.string.workspace_cwd_select_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    enabled = browsePath.isNotBlank(),
                    onClick = {
                        browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
                    },
                ) {
                    Icon(HugeIcons.ArrowTurnBackward, contentDescription = stringResource(R.string.accessibility_navigate_up_directory))
                }
                Text(
                    text = toAbsolutePath(browsePath),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Without this the picker could only ever select a folder that already existed,
                // so a feature that needs its own directory (cold memory) had nothing to point
                // at and no way to make one.
                IconButton(
                    onClick = {
                        newFolderName = ""
                        newFolderError = null
                        showNewFolder = true
                    },
                ) {
                    Icon(
                        HugeIcons.Add01,
                        contentDescription = stringResource(R.string.workspace_cwd_new_folder),
                    )
                }
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp),
            ) {
                val dirs = entries.filter { it.isDirectory }
                items(dirs, key = { it.path }) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = entry.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Folder01,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            browsePath = entry.path
                        },
                    )
                }

                if (!loading && dirs.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.workspace_cwd_no_subdirectories),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentCwd != null) {
                    TextButton(onClick = {
                        onSelectCwd(null)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.workspace_cwd_reset))
                    }
                }
                FilledTonalButton(onClick = {
                    onSelectCwd(toAbsolutePath(browsePath))
                    onDismiss()
                }) {
                    Text(stringResource(R.string.workspace_cwd_set))
                }
            }
        }
    }

    if (showNewFolder) {
        val invalidNameMessage = stringResource(R.string.workspace_cwd_new_folder_invalid)
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text(stringResource(R.string.workspace_cwd_new_folder)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = {
                            newFolderName = it
                            newFolderError = null
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.workspace_cwd_new_folder_hint)) },
                    )
                    newFolderError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newFolderName.isNotBlank(),
                    onClick = {
                        val name = newFolderName.trim()
                        if (name.isEmpty() || name == "." || name == ".." ||
                            name.contains('/') || name.contains('\\')
                        ) {
                            newFolderError = invalidNameMessage
                            return@TextButton
                        }
                        val target = if (browsePath.isBlank()) name else "$browsePath/$name"
                        scope.launch {
                            runCatching {
                                workspaceRepository.createDirectory(
                                    workspaceId,
                                    WorkspaceStorageArea.FILES,
                                    target,
                                )
                            }.onSuccess { entry ->
                                showNewFolder = false
                                // Descend into the folder just made, so the next tap on "Set"
                                // picks it — the whole point of offering the button here.
                                browsePath = entry.path
                            }.onFailure { e ->
                                newFolderError = e.message ?: invalidNameMessage
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private const val WORKSPACE_PREFIX = "/workspace"

private fun toAbsolutePath(relativePath: String): String {
    return if (relativePath.isBlank()) WORKSPACE_PREFIX else "$WORKSPACE_PREFIX/$relativePath"
}

private fun fromAbsolutePath(absolutePath: String?): String {
    if (absolutePath.isNullOrBlank()) return ""
    return absolutePath.removePrefix("$WORKSPACE_PREFIX/").removePrefix(WORKSPACE_PREFIX)
}
