package com.clma.app.ui.components.message

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clma.ai.ui.UIMessagePart
import com.clma.app.R
import com.clma.app.data.secrets.AgentSecretsStore
import com.clma.app.ui.context.LocalToaster
import com.clma.app.ui.pages.setting.authenticateForSecrets
import com.clma.app.ui.pages.setting.canAuthenticateForSecrets
import com.clma.app.ui.pages.setting.findFragmentActivity
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.koin.compose.koinInject

/** Value is read only after a local gesture + authentication, never saved in message state. */
@Composable
internal fun SecretResultCard(tool: UIMessagePart.Tool) {
    val name = (parseToolOutputContent(tool) as? JsonObject)
        ?.get("secret_name")?.jsonPrimitive?.contentOrNull ?: return
    val store: AgentSecretsStore = koinInject()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var revealed by remember(tool.toolCallId, name) { mutableStateOf<String?>(null) }
    var busy by remember(tool.toolCallId) { mutableStateOf(false) }
    val title = stringResource(R.string.setting_secrets_page_auth_title)
    val subtitle = stringResource(R.string.setting_secrets_page_auth_subtitle)
    val authUnavailable = stringResource(R.string.setting_secrets_page_auth_unavailable)
    val authFailed = stringResource(R.string.setting_secrets_page_auth_failed)
    val unavailable = stringResource(R.string.setting_secrets_page_value_unavailable)
    val copied = stringResource(R.string.setting_secrets_page_copied)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) revealed = null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            revealed = null
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun access(copy: Boolean) {
        if (busy) return
        val activity = context.findFragmentActivity()
        if (activity == null || !activity.canAuthenticateForSecrets()) {
            toaster.show(authUnavailable, ToastType.Error)
            return
        }
        busy = true
        scope.launch {
            try {
                if (!authenticateForSecrets(activity, title, subtitle)) {
                    toaster.show(authFailed, ToastType.Error)
                    return@launch
                }
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                val value = store.get(name)
                if (value == null) {
                    toaster.show(unavailable, ToastType.Error)
                } else if (copy) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("CLmA secret", value)
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                    clipboard.setPrimaryClip(clip)
                    toaster.show(copied, ToastType.Normal)
                    // Survives card disposal; never erase unrelated clipboard content.
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == value) {
                            clipboard.clearPrimaryClip()
                        }
                    }, 60_000L)
                } else {
                    revealed = value
                }
            } finally {
                busy = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.secret_result_title), style = MaterialTheme.typography.titleSmall)
            Text(name)
            Text(stringResource(R.string.setting_secrets_page_value_hidden))
            Row {
                TextButton(onClick = { access(false) }, enabled = !busy) {
                    Text(stringResource(R.string.setting_secrets_page_reveal))
                }
                TextButton(onClick = { access(true) }, enabled = !busy) {
                    Text(stringResource(R.string.setting_secrets_page_copy))
                }
            }
        }
    }
    revealed?.let { value ->
        AlertDialog(
            onDismissRequest = { revealed = null },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(name) },
            text = {
                Text(value, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = { revealed = null }) {
                    Text(stringResource(R.string.setting_secrets_page_hide))
                }
            },
        )
    }
}
