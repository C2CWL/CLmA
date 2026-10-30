package com.clma.app.ui.pages.setting

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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.clma.app.R
import com.clma.app.data.notifications.NotificationEntry
import com.clma.app.service.RikkaNotificationListenerService
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.theme.CustomColors

/**
 * Settings page for the notification listener subsystem. Shows live bound state and a
 * recent-activity log.
 */
@Composable
fun SettingNotificationsPage() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Live service handle: collected via StateFlow when the service is bound; falls back to
    // empty defaults otherwise. Recompositions key off the listener's bound flag.
    val svc = remember { RikkaNotificationListenerService.instance }
    val boundFlow: StateFlow<Boolean> = svc?.bound ?: remember { MutableStateFlow(false) }
    val bound by boundFlow.collectAsStateWithLifecycle()
    val recentFlow: StateFlow<List<NotificationEntry>> = svc?.recent ?: remember { MutableStateFlow(emptyList()) }
    val recent by recentFlow.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_notifications)) },
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
            // Status
            Text(
                text = stringResource(R.string.setting_page_notifications_status_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp)
            )
            CardGroup {
                item(
                    headlineContent = {
                        Text(
                            if (bound) stringResource(R.string.setting_page_notifications_status_connected)
                            else stringResource(R.string.setting_page_notifications_status_not_connected),
                            color = if (bound) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    supportingContent = if (!bound) ({
                        Text(stringResource(R.string.setting_page_notifications_status_help))
                    }) else null,
                )
                item(
                    onClick = {
                        context.startActivity(
                            com.clma.app.data.ai.tools.local.PermissionHelper
                                .notificationListenerSettingsIntent()
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_notifications_open_settings))
                    },
                )
            }

            // Recent activity
            Text(
                text = stringResource(R.string.setting_page_notifications_recent_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            if (recent.isEmpty()) {
                CardGroup {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_notifications_recent_empty))
                        }
                    )
                }
            } else {
                CardGroup {
                    recent.asReversed().take(50).forEach { entry ->
                        item(
                            headlineContent = {
                                Text("${entry.label}: ${entry.title.ifBlank { "(no title)" }}")
                            },
                            supportingContent = {
                                Text(
                                    text = entry.text.take(120).ifBlank { "(no text)" } + " · " +
                                        formatRelativeTime(System.currentTimeMillis() - entry.postTimeMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
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
        s < 60 -> stringResource(R.string.setting_page_notifications_action_ago_seconds, s.toInt().coerceAtLeast(0))
        s < 3600 -> stringResource(R.string.setting_page_notifications_action_ago_minutes, (s / 60).toInt())
        else -> stringResource(R.string.setting_page_notifications_action_ago_hours, (s / 3600).toInt())
    }
}
