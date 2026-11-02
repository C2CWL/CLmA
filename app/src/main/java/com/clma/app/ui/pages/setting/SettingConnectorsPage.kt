package com.clma.app.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.clma.app.connectors.GmailConnector
import com.clma.app.connectors.MailDraft
import com.clma.app.connectors.gmailAuthorizationErrorCode
import com.clma.app.ui.components.ui.AppAlertDialog
import com.clma.app.ui.components.ui.DialogPrimaryButton
import com.clma.app.ui.components.ui.DialogSecondaryButton
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingConnectorsPage() {
    val store: ConnectorStore = koinInject()
    val gmail: GmailConnector = koinInject()
    val github: GitHubConnector = koinInject()
    val revision by store.revision.collectAsStateWithLifecycle()
    val account = remember(revision) { gmail.account() }
    val githubAccount = remember(revision) { github.connection() }
    val drafts = remember(revision) { gmail.drafts() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showGmailSetup by rememberSaveable { mutableStateOf(false) }
    var pendingAccess by rememberSaveable { mutableStateOf(GmailConnector.Access.READ.name) }
    var awaitingGoogle by rememberSaveable { mutableStateOf(false) }
    var githubClientId by remember { mutableStateOf(github.clientId()) }
    var deviceCode by remember { mutableStateOf<GitHubDeviceCode?>(null) }
    var disconnect by remember { mutableStateOf<String?>(null) }
    var revokeGoogle by remember { mutableStateOf(true) }
    var confirmGithubWrites by remember { mutableStateOf(false) }
    var selectedDraft by remember { mutableStateOf<MailDraft?>(null) }
    var deleteDraft by remember { mutableStateOf<MailDraft?>(null) }
    val locked = busy || awaitingGoogle

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
            } catch (e: ApiException) {
                // Status codes are safe diagnostics; SDK messages can contain credentials.
                error = gmailAuthorizationErrorCode(e.statusCode)
            } catch (_: Exception) {
                // SDK exceptions may contain credentials; only show a stable local code.
                error = "connector_operation_failed"
            } finally {
                busy = false
            }
        }
    }

    val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        awaitingGoogle = false
        runOperation {
            val data = result.data ?: throw ConnectorException("gmail_authorization_result_missing")
            // A non-OK result can contain the SDK's failure status. Parse it before classifying.
            gmail.finishAuthorization(
                gmail.authorizationClient.getAuthorizationResultFromIntent(data),
                GmailConnector.Access.valueOf(pendingAccess),
            )
        }
    }

    fun authorizeGmail(access: GmailConnector.Access) {
        pendingAccess = access.name
        runOperation {
            val result = gmail.authorize(access)
            if (result.hasResolution()) {
                val pendingIntent = result.pendingIntent ?: throw ConnectorException("gmail_authorization_required")
                googleLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                awaitingGoogle = true
            } else {
                gmail.finishAuthorization(result, access)
            }
        }
    }

    SettingSubPageScaffold(title = stringResource(R.string.setting_connectors_title)) {
        item {
            ConnectorCard(stringResource(R.string.connector_privacy_title)) {
                Text(stringResource(R.string.connector_privacy_body), style = MaterialTheme.typography.bodySmall)
                if (locked) CircularProgressIndicator()
                error?.let { code ->
                    val message = when (code) {
                        "gmail_authorization_cancelled" -> stringResource(R.string.connector_gmail_auth_cancelled)
                        "gmail_oauth_configuration_error" -> stringResource(R.string.connector_gmail_auth_configuration_error)
                        "gmail_authorization_network_error" -> stringResource(R.string.connector_gmail_auth_network_error)
                        "gmail_authorization_result_missing" -> stringResource(R.string.connector_gmail_auth_result_missing)
                        else -> stringResource(R.string.connector_operation_error, code)
                    }
                    Text(message, color = MaterialTheme.colorScheme.error)
                    if (code.startsWith("gmail_")) {
                        TextButton(onClick = { showGmailSetup = true }) {
                            Text(stringResource(R.string.connector_gmail_setup_guide))
                        }
                    }
                }
            }
        }
        item {
            ConnectorCard("Gmail") {
                Text(account?.email ?: stringResource(R.string.connector_not_connected))
                Text(stringResource(R.string.connector_google_setup), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { showGmailSetup = true }) {
                    Text(stringResource(R.string.connector_gmail_setup_guide))
                }
                if (account == null) {
                    Button(onClick = { authorizeGmail(GmailConnector.Access.READ) }, enabled = !locked) {
                        Text(stringResource(R.string.connector_connect_readonly))
                    }
                } else {
                    ConnectorSwitch(
                        title = stringResource(R.string.connector_gmail_send),
                        checked = account.allowSend,
                        enabled = !locked,
                    ) { enabled ->
                        if (enabled) authorizeGmail(GmailConnector.Access.SEND)
                        else runOperation { gmail.disableAccess(GmailConnector.Access.SEND) }
                    }
                    ConnectorSwitch(
                        title = stringResource(R.string.connector_gmail_remote_drafts),
                        checked = account.allowRemoteDrafts,
                        enabled = !locked,
                    ) { enabled ->
                        if (enabled) authorizeGmail(GmailConnector.Access.REMOTE_DRAFTS)
                        else runOperation { gmail.disableAccess(GmailConnector.Access.REMOTE_DRAFTS) }
                    }
                    Text(stringResource(R.string.connector_gmail_scope_note), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { authorizeGmail(GmailConnector.Access.READ) }, enabled = !locked) {
                            Text(stringResource(R.string.connector_reauthorize))
                        }
                        TextButton(onClick = { revokeGoogle = true; disconnect = "gmail" }, enabled = !locked) {
                            Text(stringResource(R.string.connector_disconnect))
                        }
                    }
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
        if (drafts.isNotEmpty()) {
            item {
                ConnectorCard(stringResource(R.string.connector_local_drafts)) {
                    Text(stringResource(R.string.connector_local_drafts_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(drafts.size, key = { drafts[it].id }) { index ->
                val draft = drafts[index]
                ConnectorCard(draft.subject.ifBlank { stringResource(R.string.connector_no_subject) }) {
                    Text(draft.to)
                    Text(stringResource(R.string.connector_draft_status, draft.status))
                    OutlinedButton(onClick = { selectedDraft = draft }, enabled = !locked) {
                        Text(stringResource(R.string.connector_review_draft))
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
                    Text(stringResource(if (target == "gmail") R.string.connector_disconnect_gmail_note else R.string.connector_disconnect_github_note))
                    if (target == "gmail") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = revokeGoogle, onCheckedChange = { revokeGoogle = it })
                            Text(stringResource(R.string.connector_revoke_google))
                        }
                    }
                }
            },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    disconnect = null
                    runOperation {
                        if (target == "gmail") gmail.disconnect(revokeGoogle) else github.disconnect()
                    }
                }, destructive = true) { Text(stringResource(R.string.connector_disconnect)) }
            },
            dismissButton = {
                DialogSecondaryButton(onClick = { disconnect = null }) { Text(stringResource(R.string.connector_cancel)) }
            },
        )
    }
    if (showGmailSetup) {
        GmailSetupGuideSheet(onDismiss = { showGmailSetup = false })
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
    selectedDraft?.let { draft ->
        MailDraftSheet(
            draft = draft,
            busy = locked,
            onDismiss = { selectedDraft = null },
            onDelete = { deleteDraft = draft; selectedDraft = null },
            onSave = { to, cc, subject, body ->
                runOperation {
                    gmail.updateDraft(draft.id, to, cc, subject, body)
                    selectedDraft = null
                }
            },
        )
    }
    deleteDraft?.let { draft ->
        AppAlertDialog(
            onDismissRequest = { deleteDraft = null },
            title = { Text(stringResource(R.string.connector_delete_local_draft)) },
            text = { Text(stringResource(R.string.connector_delete_draft_note, draft.subject)) },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    deleteDraft = null
                    runOperation { gmail.deleteDraft(draft.id) }
                }, destructive = true) { Text(stringResource(R.string.connector_delete_local_draft)) }
            },
            dismissButton = {
                DialogSecondaryButton(onClick = { deleteDraft = null }) { Text(stringResource(R.string.connector_cancel)) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailDraftSheet(
    draft: MailDraft,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    var to by remember(draft.id) { mutableStateOf(draft.to) }
    var cc by remember(draft.id) { mutableStateOf(draft.cc) }
    var subject by remember(draft.id) { mutableStateOf(draft.subject) }
    var body by remember(draft.id) { mutableStateOf(draft.body) }
    val editable = draft.status == "local"
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.connector_review_draft), style = MaterialTheme.typography.titleLarge)
            Text(draft.account)
            Text(stringResource(R.string.connector_draft_status, draft.status))
            OutlinedTextField(to, { to = it }, label = { Text(stringResource(R.string.connector_to)) }, readOnly = !editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cc, { cc = it }, label = { Text(stringResource(R.string.connector_cc)) }, readOnly = !editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subject, { subject = it }, label = { Text(stringResource(R.string.connector_subject)) }, readOnly = !editable || draft.inReplyTo != null, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.connector_body)) }, readOnly = !editable, minLines = 5, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.connector_local_drafts_note), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editable) {
                    Button(onClick = { onSave(to, cc, subject, body) }, enabled = !busy) {
                        Text(stringResource(R.string.connector_save_local))
                    }
                }
                TextButton(onClick = onDelete, enabled = !busy) { Text(stringResource(R.string.connector_delete_local_draft)) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.connector_close)) }
            }
        }
    }
}
