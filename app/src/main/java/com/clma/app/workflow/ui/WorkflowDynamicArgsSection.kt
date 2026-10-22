package me.rerere.rikkahub.workflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R

/**
 * T-07 — the two halves of the "make a workflow talk to a real API" story that have no
 * chat-side equivalent:
 *
 *  - [WorkflowDynamicArgsRow] flips `use_action_templates` on one workflow. The LLM can set
 *    it through `workflow_create`/`workflow_update`, but a switch is what makes the feature
 *    discoverable (and reversible) for a human.
 *  - [WorkflowSecretsCard] is the ONLY writer for `WorkflowSecretsStore`. Without it the
 *    `{{secret:NAME}}` reference would be unusable, because nothing else in the app ever
 *    calls `set()` — the whole point of the store is that the value never has to be typed
 *    into the (model-visible, run-history-visible) workflow JSON.
 *
 * Both live in this new file rather than in [WorkflowDetailScreen] to keep that screen's
 * diff down to two `item { }` blocks.
 */
@Composable
fun WorkflowDynamicArgsRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.setting_page_workflow_detail_dynamic_args),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.setting_page_workflow_detail_dynamic_args_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
fun WorkflowSecretsCard(vm: WorkflowsViewModel) {
    val names by vm.secretNames.collectAsState()
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshSecretNames() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (names.isEmpty()) {
            Text(
                stringResource(R.string.setting_page_workflow_detail_secrets_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (name in names) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    TextButton(onClick = { scope.launch { vm.deleteSecret(name) } }) {
                        Text(
                            stringResource(R.string.setting_page_workflow_detail_secret_delete),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        TextButton(
            onClick = { showAdd = true },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        ) {
            Text(
                stringResource(R.string.setting_page_workflow_detail_secret_add),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showAdd) {
        AddSecretDialog(
            onDismiss = { showAdd = false },
            // Kept open on a rejected name so the user can fix it instead of retyping the value.
            onSave = { name, value ->
                scope.launch {
                    if (vm.setSecret(name, value)) showAdd = false
                }
            },
        )
    }
}

@Composable
private fun AddSecretDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    // Mirrors WorkflowSecretsStore.isValidName so a typo is caught before the write.
    val nameOk = name.isNotEmpty() && name.length <= 64 &&
        name.all { it.isLetterOrDigit() || it == '_' }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_page_workflow_detail_secret_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.setting_page_workflow_detail_secret_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.setting_page_workflow_detail_secret_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.setting_page_workflow_detail_secret_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, value) },
                enabled = nameOk && value.isNotEmpty(),
            ) {
                Text(stringResource(R.string.setting_page_workflow_detail_secret_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_page_workflow_detail_secret_cancel))
            }
        },
    )
}
