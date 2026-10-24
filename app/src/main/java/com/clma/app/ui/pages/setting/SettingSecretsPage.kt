package com.clma.app.ui.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clma.app.R
import com.clma.app.data.secrets.AgentSecretsStore
import com.clma.app.data.secrets.SecretEntry
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.context.LocalToaster
import com.clma.app.ui.theme.CustomColors
import com.clma.app.utils.writeClipboardText
import com.dokar.sonner.ToastType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import org.koin.compose.koinInject

/** Copied values are wiped from the clipboard after this long, unless something else overwrote it. */
private const val CLIPBOARD_CLEAR_DELAY_MS = 60_000L

/** Two timestamps this close are displayed identically ("never edited" vs "edited"). */
private const val TIMESTAMP_EPSILON_MS = 60_000L

private val SECRET_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

/**
 * Manage the on-device agent secret vault ([AgentSecretsStore]) — the store behind
 * `request_secret` / `password_secret` / `stdin_secret`.
 *
 * Listing, creating and overwriting are unrestricted. Reading a value back (reveal / copy) is
 * gated behind the screen lock (biometric or device credential) and re-locks as soon as the page
 * leaves the foreground, so a phone handed over unlocked does not leak stored tokens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSecretsPage() {
    val secretsStore: AgentSecretsStore = koinInject()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf(secretsStore.entries()) }
    var revealedName by remember { mutableStateOf<String?>(null) }
    var revealedValue by remember { mutableStateOf<String?>(null) }
    var authorized by remember { mutableStateOf(false) }
    var sheetName by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf<String?>(null) }

    val deletedMessage = stringResource(R.string.setting_secrets_page_deleted)
    val savedMessage = stringResource(R.string.setting_secrets_page_saved)
    val copiedMessage = stringResource(R.string.setting_secrets_page_copied)
    val authTitle = stringResource(R.string.setting_secrets_page_auth_title)
    val authSubtitle = stringResource(R.string.setting_secrets_page_auth_subtitle)
    val authFailed = stringResource(R.string.setting_secrets_page_auth_failed)
    val authUnavailable = stringResource(R.string.setting_secrets_page_auth_unavailable)
    val decryptFailed = stringResource(R.string.setting_secrets_page_value_unavailable)

    fun reload() {
        entries = secretsStore.entries()
    }

    fun hideRevealed() {
        revealedName = null
        revealedValue = null
    }

    // Leaving the page (background, screen off, navigating away) drops the unlocked state.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                authorized = false
                hideRevealed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** Runs [onGranted] immediately once unlocked in this visit, otherwise asks for the screen lock. */
    fun withAuth(onGranted: () -> Unit) {
        if (authorized) {
            onGranted()
            return
        }
        val activity = context.findFragmentActivity()
        if (activity == null || !activity.canAuthenticateForSecrets()) {
            toaster.show(authUnavailable, ToastType.Error)
            return
        }
        scope.launch {
            if (authenticateForSecrets(activity, authTitle, authSubtitle)) {
                authorized = true
                onGranted()
            } else {
                toaster.show(authFailed, ToastType.Error)
            }
        }
    }

    fun reveal(name: String) {
        withAuth {
            val value = secretsStore.get(name)
            if (value == null) {
                toaster.show(decryptFailed, ToastType.Error)
            } else {
                revealedName = name
                revealedValue = value
            }
        }
    }

    fun copyValue(name: String) {
        withAuth {
            val value = secretsStore.get(name)
            if (value == null) {
                toaster.show(decryptFailed, ToastType.Error)
                return@withAuth
            }
            context.writeClipboardText(value)
            toaster.show(copiedMessage, ToastType.Normal)
            scope.launch {
                delay(CLIPBOARD_CLEAR_DELAY_MS)
                context.clearClipboardIfUnchanged(value)
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_secrets_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCreate = true }) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.setting_secrets_page_add),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
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
                    title = {
                        Text(
                            stringResource(
                                R.string.setting_secrets_page_list_count,
                                entries.size,
                            )
                        )
                    },
                ) {
                    if (entries.isEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_secrets_page_empty)) },
                            supportingContent = {
                                Text(stringResource(R.string.setting_secrets_page_empty_hint))
                            },
                        )
                    }
                    entries.forEach { entry ->
                        val name = entry.name
                        val isRevealed = revealedName == name
                        val onToggleReveal = {
                            if (isRevealed) hideRevealed() else reveal(name)
                        }
                        item(
                            onClick = onToggleReveal,
                            leadingContent = {
                                Icon(
                                    imageVector = HugeIcons.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    Text(
                                        text = secretTimeSummary(entry),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (isRevealed) {
                                        SelectionContainer {
                                            Text(
                                                text = revealedValue.orEmpty(),
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = onToggleReveal) {
                                        Icon(
                                            imageVector = if (isRevealed) HugeIcons.ViewOff else HugeIcons.View,
                                            contentDescription = stringResource(
                                                if (isRevealed) R.string.setting_secrets_page_hide
                                                else R.string.setting_secrets_page_reveal
                                            ),
                                        )
                                    }
                                    IconButton(onClick = { sheetName = name }) {
                                        Icon(
                                            imageVector = HugeIcons.MoreVertical,
                                            contentDescription = stringResource(R.string.setting_secrets_page_more),
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
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_secrets_page_about_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_secrets_page_about_body))
                        },
                    )
                }
            }
        }
    }

    sheetName?.let { name ->
        val entry = entries.firstOrNull { it.name == name }
        ModalBottomSheet(onDismissRequest = { sheetName = null }) {
            Column(Modifier.navigationBarsPadding()) {
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { entry?.let { Text(secretTimeSummary(it)) } },
                )
                ListItem(
                    modifier = Modifier.clickable {
                        sheetName = null
                        copyValue(name)
                    },
                    leadingContent = { Icon(HugeIcons.Copy01, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.setting_secrets_page_copy)) },
                )
                ListItem(
                    modifier = Modifier.clickable {
                        sheetName = null
                        editName = name
                    },
                    leadingContent = { Icon(HugeIcons.Edit01, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.setting_secrets_page_edit)) },
                )
                ListItem(
                    modifier = Modifier.clickable {
                        sheetName = null
                        pendingDelete = name
                    },
                    leadingContent = {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.setting_secrets_page_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
    }

    if (showCreate) {
        SecretEditorDialog(
            existingName = null,
            isValidName = secretsStore::isValidName,
            onDismiss = { showCreate = false },
            onSave = { name, value ->
                if (secretsStore.set(name, value)) {
                    reload()
                    showCreate = false
                    toaster.show(savedMessage, ToastType.Success)
                }
            },
        )
    }

    editName?.let { name ->
        SecretEditorDialog(
            existingName = name,
            isValidName = secretsStore::isValidName,
            onDismiss = { editName = null },
            onSave = { _, value ->
                if (secretsStore.set(name, value)) {
                    reload()
                    editName = null
                    hideRevealed()
                    toaster.show(savedMessage, ToastType.Success)
                }
            },
        )
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_secrets_page_delete_confirm_title, name)) },
            text = { Text(stringResource(R.string.setting_secrets_page_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        secretsStore.remove(name)
                        reload()
                        if (revealedName == name) hideRevealed()
                        pendingDelete = null
                        toaster.show(deletedMessage, ToastType.Normal)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.setting_secrets_page_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_ssh_page_cancel))
                }
            },
        )
    }
}

/**
 * Create a new secret, or overwrite the value of [existingName] (the name is immutable there and
 * the old value is never shown, so "edit" means "re-enter").
 */
@Composable
private fun SecretEditorDialog(
    existingName: String?,
    isValidName: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String) -> Unit,
) {
    val editing = existingName != null
    var name by remember(existingName) { mutableStateOf(existingName.orEmpty()) }
    var value by remember(existingName) { mutableStateOf("") }
    val nameOk = isValidName(name)
    val canSave = (editing || nameOk) && value.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (editing) stringResource(R.string.setting_secrets_page_edit_title, existingName.orEmpty())
                else stringResource(R.string.setting_secrets_page_new_title)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text(stringResource(R.string.setting_secrets_page_field_name)) },
                    singleLine = true,
                    enabled = !editing,
                    isError = !editing && name.isNotEmpty() && !nameOk,
                    supportingText = {
                        Text(
                            if (!editing && name.isNotEmpty() && !nameOk) {
                                stringResource(R.string.setting_secrets_page_name_invalid)
                            } else {
                                stringResource(R.string.setting_secrets_page_name_hint)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.setting_secrets_page_field_value)) },
                    supportingText = {
                        if (editing) {
                            Text(stringResource(R.string.setting_secrets_page_value_replace_hint))
                        }
                    },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, value) }, enabled = canSave) {
                Text(stringResource(R.string.setting_ssh_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_ssh_page_cancel))
            }
        },
    )
}

@Composable
private fun secretTimeSummary(entry: SecretEntry): String {
    val created = entry.createdAt
    val updated = entry.updatedAt
    return when {
        created == null -> stringResource(R.string.setting_secrets_page_created_unknown)
        updated != null && updated - created > TIMESTAMP_EPSILON_MS -> stringResource(
            R.string.setting_secrets_page_time_both,
            formatSecretTime(created),
            formatSecretTime(updated),
        )
        else -> stringResource(R.string.setting_secrets_page_created, formatSecretTime(created))
    }
}

private fun formatSecretTime(millis: Long): String =
    SECRET_TIME_FORMATTER.format(Instant.ofEpochMilli(millis))

/** Compose's LocalContext is usually a ContextThemeWrapper, so walk the wrapper chain. */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current != null) {
        if (current is FragmentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

private fun FragmentActivity.canAuthenticateForSecrets(): Boolean =
    BiometricManager.from(this).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    ) == BiometricManager.BIOMETRIC_SUCCESS

/** Screen-lock prompt: fingerprint / face when enrolled, PIN-pattern-password otherwise. */
private suspend fun authenticateForSecrets(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
): Boolean = suspendCancellableCoroutine { continuation ->
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) continuation.resume(false)
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
    )
    continuation.invokeOnCancellation { prompt.cancelAuthentication() }
}

private fun Context.clearClipboardIfUnchanged(expected: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val current = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
    if (current != expected) return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("", "")) }
}
