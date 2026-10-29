package com.clma.app.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import com.clma.app.BuildConfig
import com.clma.app.R
import com.clma.app.data.datastore.NetworkSetting
import com.clma.app.data.network.toProxyOrNull
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.Switch
import com.clma.app.ui.context.LocalToaster
import com.clma.app.ui.hooks.rememberSyncedTextField
import com.clma.app.ui.theme.CustomColors
import com.clma.app.utils.plus
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val PROXY_TEST_URL = "https://www.google.com/generate_204"

/** Digits the watchdog seconds fields accept before flagging the value as out of range. */
private const val MAX_WATCHDOG_DIGITS = 5

@Composable
fun SettingPreferencesNetworkPage(vm: SettingVM = koinViewModel()) {
    val httpClient = koinInject<OkHttpClient>()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Local, IME-safe; re-keying on every persisted echo used to clobber in-progress input.
    val userAgentField = rememberSyncedTextField(settings.networkSetting.userAgent, key = Unit) { value ->
        vm.updateSettings { current ->
            current.copy(networkSetting = current.networkSetting.copy(userAgent = value))
        }
    }
    var proxyUrl by remember(settings.networkSetting.proxyUrl) {
        mutableStateOf(settings.networkSetting.proxyUrl)
    }
    var proxyUsername by remember(settings.networkSetting.proxyUsername) {
        mutableStateOf(settings.networkSetting.proxyUsername)
    }
    var proxyPassword by remember(settings.networkSetting.proxyPassword) {
        mutableStateOf(settings.networkSetting.proxyPassword)
    }
    var proxyUrlDraft by remember { mutableStateOf("") }
    var proxyUsernameDraft by remember { mutableStateOf("") }
    var proxyPasswordDraft by remember { mutableStateOf("") }
    var proxyPasswordVisible by remember { mutableStateOf(false) }
    var proxyDialogVisible by remember { mutableStateOf(false) }
    val defaultUserAgent = "CLmA-Android/${BuildConfig.VERSION_NAME}"
    val proxyUrlInvalid = proxyUrlDraft.isNotBlank() && proxyUrlDraft.toProxyOrNull() == null
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    var proxyTesting by remember { mutableStateOf(false) }

    fun updateUserAgent(value: String) {
        userAgentField.onTextChange(value)
    }

    fun saveProxy() {
        vm.updateSettings(
            settings.copy(
                networkSetting = settings.networkSetting.copy(
                    proxyUrl = proxyUrlDraft,
                    proxyUsername = proxyUsernameDraft,
                    proxyPassword = proxyPasswordDraft,
                ),
            )
        )
    }

    fun resetProxy() {
        proxyUrlDraft = ""
        proxyUsernameDraft = ""
        proxyPasswordDraft = ""
    }

    fun testProxy() {
        val proxy = proxyUrl.toProxyOrNull() ?: return
        if (proxyTesting) return
        proxyTesting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val testClient = httpClient.newBuilder()
                        .proxy(proxy)
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .callTimeout(15, TimeUnit.SECONDS)
                        .build()
                    testClient.newCall(
                        Request.Builder()
                            .url(PROXY_TEST_URL)
                            .head()
                            .build()
                    ).execute().use { response ->
                        if (response.code != 204) {
                            throw IOException("HTTP ${response.code} ${response.message}")
                        }
                    }
                }
            }
            result.onSuccess {
                toaster.show(
                    resources.getString(R.string.backup_page_connection_success),
                    type = ToastType.Success,
                )
            }.onFailure { error ->
                toaster.show(
                    resources.getString(
                        R.string.backup_page_connection_failed,
                        error.message.orEmpty(),
                    ),
                    type = ToastType.Error,
                )
            }
            proxyTesting = false
        }
    }

    if (proxyDialogVisible) {
        AlertDialog(
            onDismissRequest = { proxyDialogVisible = false },
            modifier = Modifier.imePadding(),
            title = {
                Text(stringResource(R.string.setting_page_preferences_network_proxy))
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = proxyUrlDraft,
                        onValueChange = { proxyUrlDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(R.string.setting_page_preferences_network_proxy))
                        },
                        placeholder = { Text("http://127.0.0.1:7890") },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (proxyUrlInvalid) {
                                        R.string.setting_page_preferences_network_proxy_invalid
                                    } else {
                                        R.string.setting_page_preferences_network_proxy_desc
                                    }
                                )
                            )
                        },
                        isError = proxyUrlInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = proxyUsernameDraft,
                        onValueChange = { proxyUsernameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.backup_page_username)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = proxyPasswordDraft,
                        onValueChange = { proxyPasswordDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.backup_page_password)) },
                        visualTransformation = if (proxyPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { proxyPasswordVisible = !proxyPasswordVisible },
                            ) {
                                Icon(
                                    imageVector = if (proxyPasswordVisible) {
                                        HugeIcons.ViewOff
                                    } else {
                                        HugeIcons.View
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = ::resetProxy,
                        modifier = Modifier.align(Alignment.End),
                        enabled = proxyUrlDraft.isNotEmpty() ||
                            proxyUsernameDraft.isNotEmpty() ||
                            proxyPasswordDraft.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveProxy()
                        proxyDialogVisible = false
                    },
                    enabled = !proxyUrlInvalid,
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_hub_network)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_first_output_timeout))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_page_preferences_network_first_output_timeout_desc))
                                SilentWatchdogSecondsField(
                                    value = settings.networkSetting.streamFirstOutputTimeoutSeconds,
                                    label = stringResource(R.string.setting_page_preferences_network_first_output_timeout_input),
                                    onCommit = { seconds ->
                                        vm.updateSettings { current ->
                                            if (current.networkSetting.streamFirstOutputTimeoutSeconds == seconds) {
                                                current
                                            } else {
                                                current.copy(
                                                    networkSetting = current.networkSetting.copy(
                                                        streamFirstOutputTimeoutSeconds = seconds,
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_stall_timeout))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_page_preferences_network_stall_timeout_desc))
                                SilentWatchdogSecondsField(
                                    value = settings.networkSetting.streamStallTimeoutSeconds,
                                    label = stringResource(R.string.setting_page_preferences_network_first_output_timeout_input),
                                    onCommit = { seconds ->
                                        vm.updateSettings { current ->
                                            if (current.networkSetting.streamStallTimeoutSeconds == seconds) {
                                                current
                                            } else {
                                                current.copy(
                                                    networkSetting = current.networkSetting.copy(
                                                        streamStallTimeoutSeconds = seconds,
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_stream_retries))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_page_preferences_network_stream_retries_desc))
                                StreamRetryCountField(
                                    value = settings.responseStreamMaxRetries,
                                    onCommit = { retries ->
                                        vm.updateSettings { current ->
                                            if (current.responseStreamMaxRetries == retries) {
                                                current
                                            } else {
                                                current.copy(responseStreamMaxRetries = retries)
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_user_agent))
                    },
                ) {
                    item(
                        headlineContent = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = userAgentField.value,
                                    onValueChange = userAgentField::onValueChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(stringResource(R.string.setting_page_preferences_network_user_agent))
                                    },
                                    placeholder = { Text(defaultUserAgent) },
                                    supportingText = {
                                        Text(
                                            stringResource(
                                                R.string.setting_page_preferences_network_user_agent_desc,
                                                defaultUserAgent,
                                            )
                                        )
                                    },
                                    singleLine = true,
                                )
                                TextButton(
                                    onClick = { updateUserAgent("") },
                                    enabled = userAgentField.text.isNotEmpty(),
                                ) {
                                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                                }
                            }
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_proxy))
                    },
                ) {
                    item(
                        onClick = {
                            proxyUrlDraft = proxyUrl
                            proxyUsernameDraft = proxyUsername
                            proxyPasswordDraft = proxyPassword
                            proxyPasswordVisible = false
                            proxyDialogVisible = true
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_config))
                        },
                        supportingContent = {
                            Text(
                                if (proxyUrl.isBlank()) {
                                    stringResource(
                                        R.string.setting_page_preferences_network_proxy_desc
                                    )
                                } else {
                                    proxyUrl
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_provider_page_test_connection))
                        },
                        trailingContent = {
                            TextButton(
                                onClick = ::testProxy,
                                enabled = proxyUrl.toProxyOrNull() != null && !proxyTesting,
                            ) {
                                if (proxyTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(stringResource(R.string.setting_provider_page_test))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Seconds field for one of the two stream-silence watchdogs.
 *
 * Two things this deliberately does *not* do, because both read as "the setting was not saved":
 * it never truncates what was typed (`take(3)` turned a typed 1200 into 120 - the default), and it
 * never silently rewrites an out-of-range value (the old `coerceIn(0, 600)` stored 600 while the
 * field kept showing 900). Anything outside the range stays on screen, flagged, and snaps back to
 * the stored value once the field loses focus. Every valid keystroke is committed, so a value typed
 * and then abandoned by navigating away is still saved.
 */
@Composable
private fun SilentWatchdogSecondsField(
    value: Int,
    label: String,
    onCommit: (Int) -> Unit,
) {
    // Local text; the persisted value only replaces it when it changes from elsewhere
    // (a late echo of an earlier keystroke must not overwrite the digits typed since).
    val field = rememberSyncedTextField(
        externalValue = value.toString(),
        key = Unit,
        expectedEcho = { typed ->
            typed.toIntOrNull()?.takeIf { it in 0..NetworkSetting.MAX_WATCHDOG_SECONDS }?.toString() ?: typed
        },
    ) { typed ->
        typed.toIntOrNull()
            ?.takeIf { it in 0..NetworkSetting.MAX_WATCHDOG_SECONDS }
            ?.let(onCommit)
    }
    val text = field.text
    val parsed = text.toIntOrNull()
    val outOfRange = parsed == null || parsed !in 0..NetworkSetting.MAX_WATCHDOG_SECONDS
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            field.onTextChange(raw.filter(Char::isDigit).take(MAX_WATCHDOG_DIGITS))
        },
        singleLine = true,
        isError = outOfRange,
        label = { Text(label) },
        supportingText = {
            Text(
                stringResource(
                    R.string.setting_page_preferences_network_seconds_range,
                    NetworkSetting.MAX_WATCHDOG_SECONDS,
                )
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                // Only invalid input snaps back; valid input has already been committed.
                if (!focusState.isFocused && outOfRange) field.replaceText(value.toString())
            },
    )
}

/**
 * Numeric field for the stream-retry count (0..10, 0 = disabled). Kept local to this page;
 * the retries govern every streaming generation regardless of provider type, which is why
 * the row lives under Network rather than Models.
 */
@Composable
private fun StreamRetryCountField(
    value: Int,
    onCommit: (Int) -> Unit,
) {
    val latestValue by rememberUpdatedState(value)
    val field = rememberSyncedTextField(
        externalValue = value.toString(),
        key = Unit,
        expectedEcho = { typed -> typed.toIntOrNull()?.coerceIn(0, 10)?.toString() ?: typed },
    ) { typed ->
        typed.toIntOrNull()?.let { parsed ->
            val normalized = parsed.coerceIn(0, 10)
            if (normalized != latestValue) onCommit(normalized)
        }
    }

    OutlinedTextField(
        value = field.text,
        onValueChange = { new ->
            field.onTextChange(new.filter { it.isDigit() }.take(2))
        },
        singleLine = true,
        label = { Text(stringResource(R.string.setting_page_preferences_network_stream_retries_input)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (!focusState.isFocused) {
                    val normalized = field.text.toIntOrNull()?.coerceIn(0, 10) ?: value
                    field.replaceText(normalized.toString())
                    if (normalized != value) onCommit(normalized)
                }
            },
    )
}
