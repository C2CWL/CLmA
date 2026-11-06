package com.clma.app.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.connectors.ConnectorException
import com.clma.app.connectors.ConnectorStore
import com.clma.app.connectors.GitHubConnector
import com.clma.app.connectors.GitHubDeviceCode
import com.clma.app.ui.components.ui.AppAlertDialog
import com.clma.app.ui.components.ui.DialogPrimaryButton
import com.clma.app.ui.components.ui.DialogSecondaryButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SettingConnectorsPage() {
    val store: ConnectorStore = koinInject()
    val github: GitHubConnector = koinInject()
    val revision by store.revision.collectAsStateWithLifecycle()
    val githubAccount = remember(revision) { github.connection() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var githubClientId by remember { mutableStateOf(github.clientId()) }
    var deviceCode by remember { mutableStateOf<GitHubDeviceCode?>(null) }
    var disconnect by remember { mutableStateOf<String?>(null) }
    var confirmGithubWrites by remember { mutableStateOf(false) }
    val locked = busy

    fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        operation = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConnectorException) {
                error = e.code
            } catch (_: Exception) {
                // SDK exceptions may contain credentials; only show a stable local code.
                error = "connector_operation_failed"
            } finally {
                busy = false
            }
        }
    }

    SettingSubPageScaffold(title = stringResource(R.string.setting_connectors_title)) {
        item {
            ConnectorCard(stringResource(R.string.connector_privacy_title)) {
                Text(stringResource(R.string.connector_privacy_body), style = MaterialTheme.typography.bodySmall)
                if (locked) CircularProgressIndicator()
                error?.let { code ->
                    Text(stringResource(R.string.connector_operation_error, code), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            ConnectorCard("GitHub") {
                Text(githubAccount?.login ?: stringResource(R.string.connector_not_connected))
                Text(stringResource(R.string.connector_github_setup), style = MaterialTheme.typography.bodySmall)
                if (githubAccount == null) {
                    OutlinedTextField(
                        value = githubClientId,
                        onValueChange = { githubClientId = it },
                        label = { Text(stringResource(R.string.connector_github_client_id)) },
                        singleLine = true,
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        runOperation {
                            github.setClientId(githubClientId.trim())
                            val code = github.beginAuthorization()
                            deviceCode = code
                            try {
                                github.completeAuthorization(code, allowWrites = false)
                            } finally {
                                deviceCode = null
                            }
                        }
                    }, enabled = !locked && githubClientId.isNotBlank()) {
                        Text(stringResource(R.string.connector_connect_readonly))
                    }
                } else {
                    ConnectorSwitch(
                        title = stringResource(R.string.connector_github_writes),
                        checked = githubAccount.allowWrites,
                        enabled = !locked,
                    ) { enabled ->
                        if (enabled) confirmGithubWrites = true
                        else runOperation { github.setWritesEnabled(false) }
                    }
                    TextButton(onClick = { disconnect = "github" }, enabled = !locked) {
                        Text(stringResource(R.string.connector_disconnect))
                    }
                }
                deviceCode?.let { code ->
                    Text(stringResource(R.string.connector_github_device_instructions))
                    SelectionContainer { Text(code.userCode, style = MaterialTheme.typography.headlineSmall) }
                    Text(code.verificationUri)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            runCatching { uriHandler.openUri(code.verificationUri) }
                                .onFailure { error = "connector_browser_unavailable" }
                        }) { Text(stringResource(R.string.connector_open_authorization)) }
                        TextButton(onClick = { operation?.cancel(); deviceCode = null }) {
                            Text(stringResource(R.string.connector_cancel))
                        }
                    }
                }
            }
        }
    disconnect?.let { target ->
        AppAlertDialog(
            onDismissRequest = { disconnect = null },
            title = { Text(stringResource(R.string.connector_disconnect)) },
            text = {
                Column {
                    Text(stringResource(R.string.connector_disconnect_github_note))
                }
            },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    disconnect = null
                    runOperation {
                        github.disconnect()
                    }
                }, destructive = true) { Text(stringResource(R.string.connector_disconnect)) }
            },
            dismissButton = {
                DialogSecondaryButton(onClick = { disconnect = null }) { Text(stringResource(R.string.connector_cancel)) }
            },
        )
    }
    if (confirmGithubWrites) {
        AppAlertDialog(
            onDismissRequest = { confirmGithubWrites = false },
            title = { Text(stringResource(R.string.connector_github_writes)) },
            text = { Text(stringResource(R.string.connector_write_approval_warning)) },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    confirmGithubWrites = false
                    runOperation { github.setWritesEnabled(true) }
                }) { Text(stringResource(R.string.connector_enable)) }
            },
            dismissButton = {
                DialogSecondaryButton(onClick = { confirmGithubWrites = false }) { Text(stringResource(R.string.connector_cancel)) }
            },
        )
    }
}

@Composable
private fun ConnectorCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ConnectorSwitch(title: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
