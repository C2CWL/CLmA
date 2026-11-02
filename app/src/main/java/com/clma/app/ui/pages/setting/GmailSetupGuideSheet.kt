package com.clma.app.ui.pages.setting

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.connectors.GmailConnector
import com.clma.app.connectors.gmailOAuthAppIdentity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GmailSetupGuideSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val identity = remember(context) { gmailOAuthAppIdentity(context) }
    val uriHandler = LocalUriHandler.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.connector_gmail_setup_guide), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.connector_gmail_guide_roles))
            GmailSetupStep(
                title = stringResource(R.string.connector_gmail_guide_user_title),
                body = stringResource(R.string.connector_gmail_guide_user_body),
            )
            GmailSetupStep(
                title = stringResource(R.string.connector_gmail_guide_project_title),
                body = stringResource(R.string.connector_gmail_guide_project_body),
                linkLabel = stringResource(R.string.connector_gmail_guide_open_console),
                url = "https://console.cloud.google.com/",
            )
            GmailSetupStep(
                title = stringResource(R.string.connector_gmail_guide_api_title),
                body = stringResource(R.string.connector_gmail_guide_api_body),
                linkLabel = stringResource(R.string.connector_gmail_guide_open_api),
                url = "https://console.cloud.google.com/apis/enableflow;apiid=gmail.googleapis.com",
            )
            GmailSetupStep(
                title = stringResource(R.string.connector_gmail_guide_branding_title),
                body = stringResource(R.string.connector_gmail_guide_branding_body),
                linkLabel = stringResource(R.string.connector_gmail_guide_open_branding),
                url = "https://console.developers.google.com/auth/branding",
            )
            GmailSetupStep(
                title = stringResource(R.string.connector_gmail_guide_audience_title),
                body = stringResource(R.string.connector_gmail_guide_audience_body),
                linkLabel = stringResource(R.string.connector_gmail_guide_open_audience),
                url = "https://console.developers.google.com/auth/audience",
            )
            GmailSetupStep(
                title = stringResource(R.string.connector_gmail_guide_scopes_title),
                body = stringResource(R.string.connector_gmail_guide_scopes_body),
                linkLabel = stringResource(R.string.connector_gmail_guide_open_scopes),
                url = "https://console.developers.google.com/auth/scopes",
            )
            GmailSetupCopyValue(stringResource(R.string.connector_gmail_guide_read_scope), GmailConnector.READ_SCOPE)
            GmailSetupStep(
                title = stringResource(R.string.connector_gmail_guide_client_title),
                body = stringResource(R.string.connector_gmail_guide_client_body),
                linkLabel = stringResource(R.string.connector_gmail_guide_open_clients),
                url = "https://console.developers.google.com/auth/clients",
            )
            GmailSetupCopyValue(stringResource(R.string.connector_gmail_guide_package), identity.packageName)
            if (identity.sha1Fingerprints.isEmpty()) {
                Text(stringResource(R.string.connector_gmail_guide_certificate_missing), color = MaterialTheme.colorScheme.error)
            } else {
                identity.sha1Fingerprints.forEach { fingerprint ->
                    GmailSetupCopyValue(stringResource(R.string.connector_gmail_guide_sha1), fingerprint)
                }
            }
            GmailSetupStep(
                title = stringResource(R.string.connector_gmail_guide_retry_title),
                body = stringResource(R.string.connector_gmail_guide_retry_body),
            )
            Text(stringResource(R.string.connector_gmail_guide_production), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.connector_gmail_guide_safety), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uriHandler.openUri("https://developer.android.com/identity/authorization") }) {
                Text(stringResource(R.string.connector_gmail_guide_official_docs))
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.connector_close)) }
        }
    }
}

@Composable
private fun GmailSetupStep(title: String, body: String, linkLabel: String? = null, url: String? = null) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        if (url != null && linkLabel != null) {
            TextButton(onClick = { uriHandler.openUri(url) }) { Text(linkLabel) }
        }
    }
}

@Composable
private fun GmailSetupCopyValue(label: String, value: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(value) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
                copied = true
            }
        }) {
            Text(stringResource(if (copied) R.string.copied else R.string.copy))
        }
    }
}
