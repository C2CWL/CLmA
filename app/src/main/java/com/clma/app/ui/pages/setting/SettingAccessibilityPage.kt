package me.rerere.rikkahub.ui.pages.setting

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.dokar.sonner.ToastType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.permissions.KeepAliveVendor
import me.rerere.rikkahub.data.permissions.keepAliveVendorOf
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.shizuku.ACCESSIBILITY_REPAIR_TIMEOUT_MS
import me.rerere.rikkahub.shizuku.ShizukuManager
import me.rerere.rikkahub.shizuku.ShizukuStatus
import me.rerere.rikkahub.shizuku.buildAccessibilityRepairCommand
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import java.io.File
import java.io.FileOutputStream

/**
 * Settings page for the screen-automation AccessibilityService.
 * Shows live running status, last 50 actions, deep-link to system settings, and a
 * diagnostic-screenshot button so the user can verify capture works without going through
 * the LLM.
 */
@Composable
fun SettingAccessibilityPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // The live service singleton is absent for a moment after every process restart, and a
    // `remember` whose key never changes would latch that first miss for the whole visit: the
    // page would then say "Not running" while the system switch is on and the tools still work.
    // So the three facts that make up the status are kept in state, re-read on resume and on a
    // light tick, and "is it enabled at all" falls back to the system setting - the page then
    // corrects itself within a second instead of lying until it is reopened.
    var liveService by remember { mutableStateOf(RikkaAccessibilityService.instance) }
    var enabledInSettings by remember {
        mutableStateOf(AccessibilityServiceHandle.isEnabledInSettings(context))
    }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // OEM power-management state; re-read on resume because the user grants it on a system page.
    var ignoresBatteryOpt by remember {
        mutableStateOf(PermissionHelper.ignoresBatteryOptimizations(context))
    }

    // Pull StateFlows from the live service if present; otherwise show empty defaults.
    val idleRunningFlow = remember { MutableStateFlow(false) }
    val idleActionsFlow = remember { MutableStateFlow<List<ActionLogEntry>>(emptyList()) }
    val runningFlow: StateFlow<Boolean> = liveService?.running ?: idleRunningFlow
    val running by runningFlow.collectAsStateWithLifecycle()

    val actionsFlow: StateFlow<List<ActionLogEntry>> = liveService?.lastActions ?: idleActionsFlow
    val actions by actionsFlow.collectAsStateWithLifecycle()

    val captureOkFmt = stringResource(R.string.setting_page_accessibility_capture_ok_toast)
    val captureFailFmt = stringResource(R.string.setting_page_accessibility_capture_fail_toast)
    val repairOkFmt = stringResource(R.string.setting_page_accessibility_repair_ok_toast)
    val repairFailFmt = stringResource(R.string.setting_page_accessibility_repair_fail_toast)
    val repairShizukuNotInstalled =
        stringResource(R.string.setting_page_accessibility_repair_shizuku_not_installed)
    val repairShizukuNotRunning =
        stringResource(R.string.setting_page_accessibility_repair_shizuku_not_running)
    val repairShizukuPermissionDenied =
        stringResource(R.string.setting_page_accessibility_repair_shizuku_permission_denied)

    // Re-check on resume so the rows update immediately after the user returns from a system
    // settings deep-link.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                liveService = RikkaAccessibilityService.instance
                enabledInSettings = AccessibilityServiceHandle.isEnabledInSettings(context)
                overlayGranted = Settings.canDrawOverlays(context)
                ignoresBatteryOpt = PermissionHelper.ignoresBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ...and while the page is on screen, so a service that attaches a beat after the first
    // composition still flips the status without the user having to reopen the page.
    LaunchedEffect(Unit) {
        while (true) {
            val svc = RikkaAccessibilityService.instance
            if (svc != liveService) liveService = svc
            if (svc == null) {
                val enabled = AccessibilityServiceHandle.isEnabledInSettings(context)
                if (enabled != enabledInSettings) enabledInSettings = enabled
            }
            delay(1_000)
        }
    }

    // Vendor-specific wording for the keep-alive card (the menu path differs per ROM). Kept as
    // a plain mapping so the vendor enum, not the composable, decides.
    val keepAliveVendor = remember { keepAliveVendorOf(Build.MANUFACTURER, Build.BRAND) }
    val keepAliveTitleRes = when (keepAliveVendor) {
        KeepAliveVendor.VIVO -> R.string.setting_page_accessibility_keepalive_title_vivo
        KeepAliveVendor.XIAOMI -> R.string.setting_page_accessibility_keepalive_title_xiaomi
        KeepAliveVendor.HUAWEI -> R.string.setting_page_accessibility_keepalive_title_huawei
        KeepAliveVendor.OPPO -> R.string.setting_page_accessibility_keepalive_title_oppo
        KeepAliveVendor.GENERIC -> R.string.setting_page_accessibility_keepalive_title_generic
    }
    val keepAliveBodyRes = when (keepAliveVendor) {
        KeepAliveVendor.VIVO -> R.string.setting_page_accessibility_keepalive_body_vivo
        KeepAliveVendor.XIAOMI -> R.string.setting_page_accessibility_keepalive_body_xiaomi
        KeepAliveVendor.HUAWEI -> R.string.setting_page_accessibility_keepalive_body_huawei
        KeepAliveVendor.OPPO -> R.string.setting_page_accessibility_keepalive_body_oppo
        KeepAliveVendor.GENERIC -> R.string.setting_page_accessibility_keepalive_body_generic
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_accessibility)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status card
            Text(
                text = stringResource(R.string.setting_page_accessibility_status_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp)
            )
            CardGroup {
                when {
                    running -> item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_status_running))
                        }
                    )

                    // Enabled in the system but not attached to this process yet: reporting that
                    // as "not running" is what made a working setup look broken.
                    enabledInSettings -> item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_status_enabled_not_connected))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_accessibility_status_enabled_help))
                        },
                    )

                    else -> item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_status_not_running))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_accessibility_status_help))
                        }
                    )
                }
                item(
                    onClick = {
                        context.startActivity(
                            PermissionHelper.accessibilitySettingsIntent()
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_accessibility_open_settings))
                    },
                )
                // The system Accessibility page can rewrite the enabled list out from under us
                // (on some OEM builds the toggle "springs back" the moment you leave that page)
                // and the only cure used to be a trip back through that same page. With Shizuku
                // already granted, this re-asserts the entry in place.
                item(
                    onClick = {
                        scope.launch {
                            val needShizuku = when (ShizukuManager.status(context)) {
                                ShizukuStatus.READY -> null
                                ShizukuStatus.NOT_INSTALLED -> repairShizukuNotInstalled
                                ShizukuStatus.NOT_RUNNING -> repairShizukuNotRunning
                                ShizukuStatus.PERMISSION_DENIED -> repairShizukuPermissionDenied
                            }
                            if (needShizuku != null) {
                                toaster.show(needShizuku, type = ToastType.Error)
                                return@launch
                            }
                            val component = ComponentName(
                                context,
                                RikkaAccessibilityService::class.java,
                            ).flattenToString()
                            val result = ShizukuManager.exec(
                                context,
                                buildAccessibilityRepairCommand(component),
                                ACCESSIBILITY_REPAIR_TIMEOUT_MS,
                            )
                            // The command cannot report whether its write landed - that is the
                            // whole reason it carries two write paths - so ask the system instead.
                            val repaired = AccessibilityServiceHandle.isEnabledInSettings(context)
                            enabledInSettings = repaired
                            liveService = RikkaAccessibilityService.instance
                            if (repaired) {
                                toaster.show(repairOkFmt)
                            } else {
                                toaster.show(
                                    String.format(repairFailFmt, result.toString().take(200)),
                                    type = ToastType.Error,
                                )
                            }
                        }
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_accessibility_repair))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_page_accessibility_repair_desc))
                    },
                )
                // Android 13+ greys out this toggle for apps installed outside an app store until
                // the user allows restricted settings. Without spelling that out, the greyed switch
                // is a dead end, so surface the recovery path and hand over the shortcut straight to
                // the app info page where the ⋮ menu lives.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_restricted_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_accessibility_restricted_help))
                        },
                    )
                    item(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_open_app_info))
                        },
                    )
                }
            }

            // Keep-alive card: aggressive OEM power management kills the app, and the bound
            // accessibility service silently disappears with it (tools then fail with "not
            // active" while the system toggle still reads ON). No API can prevent this, so the
            // best we can do is spell out the vendor's allow-list and offer the two shortcuts
            // we do have: the battery-optimization prompt and the app-info page.
            Text(
                text = stringResource(R.string.setting_page_accessibility_keepalive_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            CardGroup {
                item(
                    headlineContent = {
                        Text(stringResource(keepAliveTitleRes))
                    },
                    supportingContent = {
                        Text(stringResource(keepAliveBodyRes))
                    },
                )
                item(
                    onClick = {
                        context.startActivity(
                            PermissionHelper.requestIgnoreBatteryOptimizationsIntent(context)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    headlineContent = {
                        Text(
                            if (ignoresBatteryOpt) {
                                stringResource(R.string.setting_page_accessibility_keepalive_battery_ok)
                            } else {
                                stringResource(R.string.setting_page_accessibility_keepalive_battery_needed)
                            }
                        )
                    },
                )
                item(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_accessibility_keepalive_open_app_info))
                    },
                )
            }

            // Activity overlay card
            Text(
                text = stringResource(R.string.setting_page_accessibility_overlay_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            CardGroup {
                if (overlayGranted) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_overlay_granted))
                        }
                    )
                } else {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_overlay_not_granted))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_accessibility_overlay_help))
                        }
                    )
                    item(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_overlay_open_settings))
                        },
                    )
                }
            }

            // Diagnostics card
            Text(
                text = stringResource(R.string.setting_page_accessibility_diagnostics_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            CardGroup {
                item(
                    onClick = {
                        scope.launch {
                            val live = RikkaAccessibilityService.instance
                            if (live == null) {
                                toaster.show(
                                    String.format(captureFailFmt, "service not active"),
                                    type = ToastType.Error,
                                )
                                return@launch
                            }
                            val result = live.captureScreenshot(0)
                            when (result) {
                                is RikkaAccessibilityService.ScreenshotOutcome.Success -> {
                                    val cacheDir = File(context.cacheDir, "screenshots")
                                        .apply { mkdirs() }
                                    val ts = System.currentTimeMillis()
                                    val file = File(cacheDir, "diag-$ts.png")
                                    try {
                                        FileOutputStream(file).use { os ->
                                            result.bitmap.compress(
                                                android.graphics.Bitmap.CompressFormat.PNG, 100, os
                                            )
                                        }
                                    } finally {
                                        result.bitmap.recycle()
                                    }
                                    toaster.show(String.format(captureOkFmt, file.name))
                                }
                                is RikkaAccessibilityService.ScreenshotOutcome.Failure -> {
                                    toaster.show(
                                        String.format(captureFailFmt, result.reason),
                                        type = ToastType.Error,
                                    )
                                }
                            }
                        }
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_accessibility_capture_diag))
                    },
                )
            }

            // Recent actions card
            Text(
                text = stringResource(R.string.setting_page_accessibility_recent_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            if (actions.isEmpty()) {
                CardGroup {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_no_actions))
                        }
                    )
                }
            } else {
                CardGroup {
                    actions.asReversed().forEach { entry ->
                        item(
                            headlineContent = { Text("${entry.type}: ${entry.paramsSummary}") },
                            supportingContent = {
                                Text(formatRelativeTime(System.currentTimeMillis() - entry.timestampMs))
                            },
                            trailingContent = {
                                Text(
                                    text = if (entry.success) "OK" else "FAIL",
                                    color = if (entry.success)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun formatRelativeTime(deltaMs: Long): String {
    val s = deltaMs / 1000
    return when {
        s < 60 -> stringResource(R.string.setting_page_accessibility_action_ago_seconds, s.toInt().coerceAtLeast(0))
        s < 3600 -> stringResource(R.string.setting_page_accessibility_action_ago_minutes, (s / 60).toInt())
        else -> stringResource(R.string.setting_page_accessibility_action_ago_hours, (s / 3600).toInt())
    }
}
