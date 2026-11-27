package com.clma.app.ui.pages.setting

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.plugins.PluginRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SettingPluginsPage() {
    val repository: PluginRepository = koinInject()
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var pendingInstall by remember { mutableStateOf<Uri?>(null) }
    var pendingUninstall by remember { mutableStateOf<String?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }

    val pickPlugin = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingInstall = uri }

    val surfaces = remember(state.plugins) { repository.settingsSurfaces() }

    SettingSubPageScaffold(title = stringResource(R.string.setting_plugins_title)) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_plugins_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            pickPlugin.launch(arrayOf("application/vnd.android.package-archive"))
                        },
                    ) {
                        Text(stringResource(R.string.setting_plugins_install))
                    }
                    installError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (state.plugins.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.setting_plugins_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        items(state.plugins, key = { it.id }) { plugin ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.setting_plugins_name_version,
                            plugin.name,
                            plugin.version,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (plugin.description.isNotBlank()) {
                        Text(plugin.description, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = stringResource(R.string.setting_plugins_id, plugin.id),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Switch(
                            checked = plugin.enabled,
                            onCheckedChange = { repository.setEnabled(plugin.id, it) },
                        )
                        Text(
                            text = stringResource(
                                if (plugin.enabled) {
                                    R.string.setting_plugins_enabled
                                } else {
                                    R.string.setting_plugins_disabled
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { pendingUninstall = plugin.id }) {
                            Text(stringResource(R.string.setting_plugins_uninstall))
                        }
                    }
                    plugin.error?.let { error ->
                        Text(
                            text = stringResource(R.string.setting_plugins_error, error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        items(surfaces, key = { it.entry.id }) { surface ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.setting_plugins_settings,
                            surface.entry.name,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    AndroidView(
                        factory = { androidContext -> surface.surface.createView(androidContext) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    pendingInstall?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingInstall = null },
            title = { Text(stringResource(R.string.setting_plugins_install)) },
            text = { Text(stringResource(R.string.setting_plugins_install_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingInstall = null
                        scope.launch {
                            runCatching { repository.install(uri) }
                                .onFailure { error ->
                                    installError = error.message ?: "Install failed"
                                }
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingInstall = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    pendingUninstall?.let { pluginId ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(stringResource(R.string.setting_plugins_uninstall)) },
            text = { Text(stringResource(R.string.setting_plugins_uninstall_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.uninstall(pluginId)
                        pendingUninstall = null
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
