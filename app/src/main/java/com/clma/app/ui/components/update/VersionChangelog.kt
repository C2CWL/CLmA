package com.clma.app.ui.components.update

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clma.app.BuildConfig
import com.clma.app.R
import com.clma.app.ui.hooks.readStringPreference
import com.clma.app.ui.hooks.writeStringPreference
import com.clma.app.utils.openUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val CHANGELOG_DISMISSED_VERSION = "changelog.dismissedVersionCode"

internal fun shouldShowChangelog(versionCode: String, dismissedVersion: String?): Boolean =
    versionCode != dismissedVersion

/** One presentation per Activity session; only an explicit checked confirmation persists. */
@Composable
fun VersionChangelogPrompt(eligible: Boolean) {
    val context = LocalContext.current
    var closed by rememberSaveable { mutableStateOf(false) }
    val suppressed = remember { context.readStringPreference(CHANGELOG_DISMISSED_VERSION) }
    if (eligible && !closed && shouldShowChangelog(BuildConfig.VERSION_CODE.toString(), suppressed)) {
        VersionChangelogDialog(showSuppression = true) { suppress ->
            if (suppress) context.writeStringPreference(CHANGELOG_DISMISSED_VERSION, BuildConfig.VERSION_CODE.toString())
            closed = true
        }
    }
}

@Composable
fun VersionChangelogDialog(showSuppression: Boolean = false, onDismiss: (Boolean) -> Unit) {
    val context = LocalContext.current
    var suppress by rememberSaveable { mutableStateOf(false) }
    val fallback = stringResource(R.string.changelog_unavailable)
    val notes by produceState<String?>(null, BuildConfig.VERSION_NAME) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open("changelog/${BuildConfig.VERSION_NAME}.txt").bufferedReader().use { it.readText() }
                    .takeIf { it.isNotBlank() }
            } catch (_: java.io.IOException) { null }
        } ?: fallback
    }
    AlertDialog(
        onDismissRequest = { onDismiss(false) },
        title = { Text(stringResource(R.string.changelog_title, BuildConfig.VERSION_NAME)) },
        text = {
            Column {
                Text(
                    notes ?: stringResource(R.string.changelog_loading),
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
                if (showSuppression) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().toggleable(value = suppress, role = Role.Checkbox, onValueChange = { suppress = it }),
                ) {
                    Checkbox(checked = suppress, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.changelog_suppress))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(suppress) }) { Text(stringResource(R.string.changelog_confirm)) } },
        dismissButton = { TextButton(onClick = {
            context.openUrl("https://github.com/C2CWL/CLmA/releases/tag/v${BuildConfig.VERSION_NAME}")
        }) { Text(stringResource(R.string.changelog_release_page)) } },
    )
}
