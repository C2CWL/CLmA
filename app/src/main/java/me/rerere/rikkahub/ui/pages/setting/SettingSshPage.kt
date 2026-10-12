package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.ssh.KnownHostEntry
import me.rerere.rikkahub.data.ssh.SshAuthMethod
import me.rerere.rikkahub.data.ssh.SshHostDraft
import me.rerere.rikkahub.data.ssh.SshHostDraftValidator
import me.rerere.rikkahub.data.ssh.SshHostError
import me.rerere.rikkahub.data.ssh.SshHostField
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * P2-32: manage the saved SSH hosts (Room `ssh_hosts`) that the local SSH tools use.
 *
 * Sits between "Sub-agent profiles" and "Web Server" in Settings → Models & services.
 */
@Composable
fun SettingSshPage(vm: SettingSshViewModel = koinViewModel()) {
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val knownHosts by vm.knownHosts.collectAsStateWithLifecycle()
    val testingName by vm.testingName.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    var editorDraft by remember { mutableStateOf<SshHostDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<SshHostEntity?>(null) }
    var pendingForget by remember { mutableStateOf<KnownHostEntry?>(null) }

    val savedMessage = stringResource(R.string.setting_ssh_page_saved)
    val deletedMessage = stringResource(R.string.setting_ssh_page_deleted)
    val forgottenMessage = stringResource(R.string.setting_ssh_page_key_forgotten)

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_ssh_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editorDraft = SshHostDraft() },
                icon = { Icon(HugeIcons.Add01, null) },
                text = { Text(stringResource(R.string.setting_ssh_page_add)) },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_ssh_page_hosts)) },
                ) {
                    if (hosts.isEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_ssh_page_empty)) },
                            supportingContent = { Text(stringResource(R.string.setting_ssh_page_empty_hint)) },
                        )
                    }
                    hosts.forEach { host ->
                        val result = testResult?.takeIf { it.first == host.name }?.second
                        item(
                            headlineContent = { Text(host.name) },
                            overlineContent = { Text(sshAuthLabel(host)) },
                            supportingContent = {
                                Column {
                                    Text("${host.user}@${host.host}:${host.port}")
                                    if (result != null) {
                                        Text(
                                            text = sshTestLabel(result),
                                            color = if (result.ok) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        // The failure code alone ("connect_failed") says nothing
                                        // about why: show the underlying reason the connection
                                        // threw (timeout, auth, host key, unreachable transport).
                                        result.detail?.takeIf { !result.ok }?.let { reason ->
                                            Text(
                                                text = reason,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (testingName == host.name) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 3.dp,
                                        )
                                    } else {
                                        IconButton(onClick = { vm.test(host) }) {
                                            Icon(
                                                imageVector = HugeIcons.Play,
                                                contentDescription = stringResource(R.string.setting_ssh_page_test),
                                            )
                                        }
                                    }
                                    IconButton(onClick = { editorDraft = host.toDraft() }) {
                                        Icon(
                                            imageVector = HugeIcons.Edit01,
                                            contentDescription = stringResource(R.string.setting_ssh_page_edit),
                                        )
                                    }
                                    IconButton(onClick = { pendingDelete = host }) {
                                        Icon(
                                            imageVector = HugeIcons.Delete01,
                                            contentDescription = stringResource(R.string.setting_ssh_page_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_ssh_page_known_hosts)) },
                ) {
                    if (knownHosts.isEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_ssh_page_known_hosts_empty)) },
                            supportingContent = { Text(stringResource(R.string.setting_ssh_page_known_hosts_empty_hint)) },
                        )
                    }
                    knownHosts.forEach { entry ->
                        item(
                            headlineContent = { Text(entry.pattern) },
                            supportingContent = { Text(entry.keyType) },
                            trailingContent = {
                                if (!entry.isHashed) {
                                    IconButton(onClick = { pendingForget = entry }) {
                                        Icon(
                                            imageVector = HugeIcons.Delete01,
                                            contentDescription = stringResource(R.string.setting_ssh_page_forget_key),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                        )
                    }
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.setting_ssh_page_secrets_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }

    editorDraft?.let { draft ->
        SshHostEditorSheet(
            initialDraft = draft,
            existingNames = hosts.map { it.name }.toSet(),
            onDismiss = { editorDraft = null },
            onSave = { edited ->
                vm.save(edited)
                editorDraft = null
                scope.launch { toaster.show(savedMessage, type = ToastType.Success) }
            },
        )
    }

    pendingDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_ssh_page_delete_confirm_title)) },
            text = { Text(stringResource(R.string.setting_ssh_page_delete_confirm_message, host.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(host)
                    pendingDelete = null
                    scope.launch { toaster.show(deletedMessage, type = ToastType.Success) }
                }) {
                    Text(stringResource(R.string.setting_ssh_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_ssh_page_cancel))
                }
            },
        )
    }

    pendingForget?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingForget = null },
            title = { Text(stringResource(R.string.setting_ssh_page_forget_key_confirm_title)) },
            text = { Text(stringResource(R.string.setting_ssh_page_forget_key_confirm_message, entry.pattern)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.forgetKey(entry)
                    pendingForget = null
                    scope.launch { toaster.show(forgottenMessage, type = ToastType.Success) }
                }) {
                    Text(stringResource(R.string.setting_ssh_page_forget_key))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingForget = null }) {
                    Text(stringResource(R.string.setting_ssh_page_cancel))
                }
            },
        )
    }
}

private fun SshHostEntity.toDraft(): SshHostDraft {
    val method = if (!privateKey.isNullOrBlank()) SshAuthMethod.PRIVATE_KEY else SshAuthMethod.PASSWORD
    return SshHostDraft(
        originalName = name,
        name = name,
        host = host,
        port = port.toString(),
        user = user,
        authMethod = method,
        password = password.orEmpty(),
        privateKey = privateKey.orEmpty(),
        passphrase = passphrase.orEmpty(),
    )
}

@Composable
private fun sshAuthLabel(host: SshHostEntity): String = stringResource(
    if (!host.privateKey.isNullOrBlank()) {
        R.string.setting_ssh_page_auth_key
    } else {
        R.string.setting_ssh_page_auth_password
    }
)

@Composable
private fun sshTestLabel(result: SshTestResult): String {
    if (result.ok) return stringResource(R.string.setting_ssh_page_test_ok)
    val reason = result.errorCode ?: "error"
    return stringResource(R.string.setting_ssh_page_test_failed, reason)
}

@Composable
private fun sshErrorLabel(error: SshHostError): String = stringResource(
    when (error) {
        SshHostError.NAME_BLANK -> R.string.setting_ssh_page_error_name_blank
        SshHostError.NAME_TAKEN -> R.string.setting_ssh_page_error_name_taken
        SshHostError.HOST_BLANK -> R.string.setting_ssh_page_error_host_blank
        SshHostError.PORT_INVALID -> R.string.setting_ssh_page_error_port_invalid
        SshHostError.USER_BLANK -> R.string.setting_ssh_page_error_user_blank
        SshHostError.CREDENTIAL_MISSING -> R.string.setting_ssh_page_error_credential_missing
    }
)

/**
 * Build the `supportingText` slot for one editor row, or null when the field is fine.
 *
 * The explicit `@Composable () -> Unit` local type is what lets a slot-less lambda be returned
 * from a @Composable function without the compiler dropping the composable annotation.
 */
@Composable
private fun errorSupporting(error: SshHostError?): (@Composable () -> Unit)? {
    if (error == null) return null
    val block: @Composable () -> Unit = { Text(sshErrorLabel(error)) }
    return block
}

@Composable
private fun SshHostEditorSheet(
    initialDraft: SshHostDraft,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (SshHostDraft) -> Unit,
) {
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }
    val errors = SshHostDraftValidator.validate(draft, existingNames)
    val isNew = initialDraft.isNew

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (isNew) R.string.setting_ssh_page_add else R.string.setting_ssh_page_edit
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text(stringResource(R.string.setting_ssh_page_name)) },
                singleLine = true,
                isError = errors.containsKey(SshHostField.NAME),
                supportingText = errorSupporting(errors[SshHostField.NAME]),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.host,
                onValueChange = { draft = draft.copy(host = it) },
                label = { Text(stringResource(R.string.setting_ssh_page_host)) },
                singleLine = true,
                isError = errors.containsKey(SshHostField.HOST),
                supportingText = errorSupporting(errors[SshHostField.HOST]),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.port,
                    onValueChange = { value -> draft = draft.copy(port = value.filter { it.isDigit() }) },
                    label = { Text(stringResource(R.string.setting_ssh_page_port)) },
                    singleLine = true,
                    isError = errors.containsKey(SshHostField.PORT),
                    supportingText = errorSupporting(errors[SshHostField.PORT]),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp),
                )
                OutlinedTextField(
                    value = draft.user,
                    onValueChange = { draft = draft.copy(user = it) },
                    label = { Text(stringResource(R.string.setting_ssh_page_user)) },
                    singleLine = true,
                    isError = errors.containsKey(SshHostField.USER),
                    supportingText = errorSupporting(errors[SshHostField.USER]),
                    modifier = Modifier.weight(1f),
                )
            }

            Select(
                options = listOf(SshAuthMethod.PASSWORD, SshAuthMethod.PRIVATE_KEY),
                selectedOption = draft.authMethod,
                onOptionSelected = { draft = draft.copy(authMethod = it) },
                optionToString = {
                    stringResource(
                        if (it == SshAuthMethod.PASSWORD) {
                            R.string.setting_ssh_page_auth_password
                        } else {
                            R.string.setting_ssh_page_auth_key
                        }
                    )
                },
            )

            when (draft.authMethod) {
                SshAuthMethod.PASSWORD -> OutlinedTextField(
                    value = draft.password,
                    onValueChange = { draft = draft.copy(password = it) },
                    label = { Text(stringResource(R.string.setting_ssh_page_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = errors.containsKey(SshHostField.CREDENTIAL),
                    supportingText = errorSupporting(errors[SshHostField.CREDENTIAL]),
                    modifier = Modifier.fillMaxWidth(),
                )

                SshAuthMethod.PRIVATE_KEY -> {
                    OutlinedTextField(
                        value = draft.privateKey,
                        onValueChange = { draft = draft.copy(privateKey = it) },
                        label = { Text(stringResource(R.string.setting_ssh_page_private_key)) },
                        minLines = 3,
                        isError = errors.containsKey(SshHostField.CREDENTIAL),
                    supportingText = errorSupporting(errors[SshHostField.CREDENTIAL]),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.passphrase,
                        onValueChange = { draft = draft.copy(passphrase = it) },
                        label = { Text(stringResource(R.string.setting_ssh_page_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.setting_ssh_page_cancel))
                }
                Button(
                    onClick = { onSave(draft) },
                    enabled = errors.isEmpty(),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.setting_ssh_page_save))
                }
            }
        }
    }
}
