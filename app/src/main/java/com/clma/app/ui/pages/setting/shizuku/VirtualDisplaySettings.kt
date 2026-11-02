package com.clma.app.ui.pages.setting.shizuku

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.ui.pages.setting.SettingSubPageScaffold
import com.clma.app.R
import com.clma.app.data.ai.tools.local.VirtualDisplayController
import com.clma.app.ui.components.ui.AppAlertDialog
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.DialogPrimaryButton
import com.clma.app.ui.components.ui.DialogSecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VirtualDisplaysPage() {
    SettingSubPageScaffold(title = stringResource(R.string.virtual_display_manage_title)) {
        item { VirtualDisplaySettings() }
    }
}

@Composable
internal fun VirtualDisplaySettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val displays by VirtualDisplayController.activeDisplays.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingStop by remember { mutableStateOf<VirtualDisplayController.DisplayInfo?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardGroup(title = { Text(stringResource(R.string.virtual_display_manage_title)) }) {
            item(
                headlineContent = { Text(stringResource(R.string.virtual_display_manage_title)) },
                supportingContent = { Text(stringResource(R.string.virtual_display_manage_description)) },
            )
        }
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            VirtualDisplayController.create(context.applicationContext, 720, 1280, 320)
                        }
                        if (result is VirtualDisplayController.Result.Error) {
                            error = result.message
                        }
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            Text(stringResource(R.string.virtual_display_create_manual))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (displays.isEmpty()) {
            Text(
                stringResource(R.string.virtual_display_none),
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        displays.forEach { display ->
            CardGroup {
                item(
                    headlineContent = {
                        Text(stringResource(R.string.virtual_display_display_info, display.displayId,
                            display.width, display.height, display.densityDpi))
                    },
                    supportingContent = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                if (Settings.canDrawOverlays(context)) {
                                    error = null
                                    VirtualDisplayController.showPreview(context, display.displayId)
                                } else {
                                    error = context.getString(R.string.virtual_display_overlay_required)
                                }
                            }) {
                                Text(stringResource(R.string.virtual_display_show_preview))
                            }
                            TextButton(onClick = {
                                VirtualDisplayController.hidePreview(display.displayId)
                            }) {
                                Text(stringResource(R.string.virtual_display_hide_preview))
                            }
                            TextButton(enabled = !busy, onClick = { pendingStop = display }) {
                                Text(stringResource(R.string.virtual_display_stop),
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                )
            }
        }
    }
    pendingStop?.let { display ->
        AppAlertDialog(
            onDismissRequest = { pendingStop = null },
            title = { Text(stringResource(R.string.virtual_display_stop)) },
            text = { Text(stringResource(R.string.virtual_display_stop_warning, display.displayId)) },
            confirmButton = {
                DialogPrimaryButton(destructive = true, onClick = {
                    pendingStop = null
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                VirtualDisplayController.destroy(display.displayId)
                            }
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.virtual_display_stop)) }
            },
            dismissButton = {
                DialogSecondaryButton(onClick = { pendingStop = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
