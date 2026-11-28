package com.clma.app.ui.pages.setting

import android.content.Intent
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.plugins.PluginInstallability
import com.clma.app.data.plugins.PluginMarketDownloader
import com.clma.app.data.plugins.PluginMarketEntry
import com.clma.app.data.plugins.PluginMarketRepository
import com.clma.app.data.plugins.PluginPackageAdapterRegistry
import com.clma.app.data.plugins.PluginPackageNextStep
import com.clma.app.data.plugins.PluginRepository
import java.io.File
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SettingPluginsPage() {
    val repository: PluginRepository = koinInject()
    val marketRepository: PluginMarketRepository = koinInject()
    val packageAdapters: PluginPackageAdapterRegistry = koinInject()
    val marketDownloader: PluginMarketDownloader = koinInject()
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var pendingInstall by remember { mutableStateOf<Uri?>(null) }
    var pendingUninstall by remember { mutableStateOf<String?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }

    var marketQuery by remember { mutableStateOf("") }
    var marketItems by remember { mutableStateOf<List<PluginMarketEntry>>(emptyList()) }
    var marketLoading by remember { mutableStateOf(true) }
    var marketError by remember { mutableStateOf<String?>(null) }
    var pendingMarketInstall by remember { mutableStateOf<PluginMarketEntry?>(null) }
    var marketInstallError by remember { mutableStateOf<String?>(null) }
    var marketInstalling by remember { mutableStateOf(false) }

    fun loadMarket(query: String) {
        scope.launch {
            marketLoading = true
            marketError = null
            runCatching { marketRepository.load(query) }
                .onSuccess { result ->
                    marketItems = result.items
                    marketError = result.errors
                        .joinToString("; ") { "${it.source}: ${it.message}" }
                        .takeIf { it.isNotBlank() }
                }
                .onFailure { marketError = it.message ?: "Market load failed" }
            marketLoading = false
        }
    }

    LaunchedEffect(Unit) { loadMarket("") }

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

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_plugins_market_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.setting_plugins_market_not_native),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = marketQuery,
                        onValueChange = { marketQuery = it },
                        label = { Text(stringResource(R.string.setting_plugins_market_search_hint)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Search,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { loadMarket(marketQuery) },
                            enabled = !marketLoading,
                        ) {
                            Text(stringResource(R.string.setting_plugins_market_search))
                        }
                        TextButton(
                            onClick = {
                                marketQuery = ""
                                loadMarket("")
                            },
                            enabled = !marketLoading,
                        ) {
                            Text(stringResource(R.string.setting_plugins_market_clear))
                        }
                    }
                    if (marketLoading) {
                        CircularProgressIndicator()
                    }
                    marketError?.let { error ->
                        Text(
                            text = stringResource(R.string.setting_plugins_market_error, error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!marketLoading && marketError == null && marketItems.isEmpty()) {
                        Text(
                            text = stringResource(R.string.setting_plugins_market_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    marketItems.forEach { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.setting_plugins_market_name_version,
                                        entry.name,
                                        entry.version ?: "—",
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (entry.description.isNotBlank()) {
                                    Text(entry.description, style = MaterialTheme.typography.bodyMedium)
                                }
                                val plan = packageAdapters.plan(entry)
                                Text(
                                    text = stringResource(R.string.setting_plugins_market_type, entry.type),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.setting_plugins_market_source,
                                        entry.sourceLabel,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.setting_plugins_market_compat,
                                        installabilityLabel(plan.installability),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.setting_plugins_market_next_step,
                                        nextStepLabel(plan.nextStep),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                entry.publisher?.let { publisher ->
                                    Text(
                                        text = stringResource(
                                            R.string.setting_plugins_market_publisher,
                                            publisher,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (entry.verified) {
                                    Text(
                                        text = stringResource(R.string.setting_plugins_market_verified),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (plan.canInstallInApp && entry.downloadUrl != null) {
                                        Button(
                                            onClick = {
                                                pendingMarketInstall = entry
                                                marketInstallError = null
                                            },
                                            enabled = !marketInstalling,
                                        ) {
                                            Text(stringResource(R.string.setting_plugins_market_install))
                                        }
                                    }
                                    entry.repoUrl?.let { url ->
                                        TextButton(
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                                    )
                                                }
                                            },
                                        ) {
                                            Text(stringResource(R.string.setting_plugins_market_open_source))
                                        }
                                    }
                                    entry.installCommand?.let { command ->
                                        TextButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(command))
                                            },
                                        ) {
                                            Text(stringResource(R.string.setting_plugins_market_copy_command))
                                        }
                                    }
                                }
                            }
                        }
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

    pendingMarketInstall?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!marketInstalling) pendingMarketInstall = null },
            title = { Text(stringResource(R.string.setting_plugins_market_install_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.setting_plugins_market_install_confirm,
                            entry.name,
                            entry.sourceLabel,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.setting_plugins_market_install_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    marketInstallError?.let { error ->
                        Text(
                            stringResource(R.string.setting_plugins_market_install_failed, error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !marketInstalling,
                    onClick = {
                        val target = entry
                        marketInstalling = true
                        marketInstallError = null
                        scope.launch {
                            var downloaded: File? = null
                            runCatching {
                                downloaded = marketDownloader.download(target)
                                repository.install(Uri.fromFile(downloaded!!))
                            }.onSuccess {
                                pendingMarketInstall = null
                            }.onFailure { error ->
                                marketInstallError = error.message ?: "Install failed"
                            }
                            downloaded?.delete()
                            marketInstalling = false
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (marketInstalling) {
                                R.string.setting_plugins_market_installing
                            } else {
                                R.string.setting_plugins_market_install
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !marketInstalling,
                    onClick = { pendingMarketInstall = null },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

@Composable
private fun installabilityLabel(value: PluginInstallability): String = stringResource(
    when (value) {
        PluginInstallability.DIRECT_APK -> R.string.setting_plugins_compat_direct_apk
        PluginInstallability.MANUAL_BUILD_REQUIRED -> R.string.setting_plugins_compat_manual_build
        PluginInstallability.EXTERNAL_RUNTIME_ONLY -> R.string.setting_plugins_compat_external_runtime
        PluginInstallability.SOURCE_ONLY -> R.string.setting_plugins_compat_source_only
    },
)

@Composable
private fun nextStepLabel(value: PluginPackageNextStep): String = stringResource(
    when (value) {
        PluginPackageNextStep.INSTALL_APK -> R.string.setting_plugins_step_install_apk
        PluginPackageNextStep.BUILD_NATIVE_APK -> R.string.setting_plugins_step_build_native
        PluginPackageNextStep.USE_EXTERNAL_RUNTIME -> R.string.setting_plugins_step_external
        PluginPackageNextStep.OPEN_SOURCE -> R.string.setting_plugins_step_open_source
    },
)
