package com.clma.app.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.clma.app.R
import com.clma.app.data.permissions.PermissionInventory
import com.clma.app.data.permissions.PermissionInventory.GrantAction
import com.clma.app.data.permissions.PermissionInventory.Group
import com.clma.app.data.permissions.PermissionInventory.Status
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.theme.CustomColors

/**
 * Auto-discovered permission overview. Reads every <uses-permission> the app declares plus
 * two virtual rows for the AccessibilityService / NotificationListener bindings, classifies
 * them by how the user grants each, and surfaces a tap-to-grant button per row.
 *
 * Future-proof: when a new <uses-permission> is added to the manifest it shows up here
 * automatically. The friendly-label map covers our currently-used dangerous perms; unmapped
 * ones fall back to a humanized constant name.
 */
@Composable
fun SettingPermissionsPage() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var rows by remember { mutableStateOf(PermissionInventory.build(context)) }

    val refreshNow = {
        rows = PermissionInventory.build(context)
    }

    val runtimeRequester = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        refreshNow()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val grouped = rows.groupBy { it.group }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_hub_permissions)) },
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
            Group.values().forEach { group ->
                val rowsForGroup = grouped[group] ?: return@forEach
                if (rowsForGroup.isEmpty()) return@forEach
                Text(
                    text = sectionTitle(group),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
                CardGroup {
                    rowsForGroup.forEach { row ->
                        val grantAction = row.grant
                        val click: (() -> Unit)? = when {
                            grantAction is GrantAction.Runtime
                                && row.status != Status.GRANTED -> {
                                { runtimeRequester.launch(grantAction.permission) }
                            }
                            grantAction is GrantAction.SystemSettings -> {
                                { context.startActivity(grantAction.intent) }
                            }
                            else -> null
                        }
                        item(
                            onClick = click,
                            headlineContent = { Text(row.label) },
                            supportingContent = { Text(row.description) },
                            trailingContent = {
                                val isActionable = row.grant !is GrantAction.None
                                val statusText = when {
                                    row.status == Status.GRANTED -> stringResource(R.string.setting_page_permissions_status_granted)
                                    row.status == Status.AUTO_GRANTED -> stringResource(R.string.setting_page_permissions_status_auto_granted)
                                    !isActionable -> stringResource(R.string.setting_page_permissions_status_not_granted)
                                    else -> stringResource(R.string.setting_page_permissions_status_denied)
                                }
                                val tint = when {
                                    row.status == Status.GRANTED -> MaterialTheme.colorScheme.primary
                                    row.status == Status.AUTO_GRANTED || !isActionable -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.error
                                }
                                Text(
                                    text = statusText,
                                    color = tint,
                                    style = MaterialTheme.typography.labelSmall,
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
private fun sectionTitle(group: Group): String = when (group) {
    Group.ServicesAndIntegrations ->
        stringResource(R.string.setting_page_permissions_group_services)
    Group.SpecialAccess ->
        stringResource(R.string.setting_page_permissions_group_special)
    Group.Runtime ->
        stringResource(R.string.setting_page_permissions_group_runtime)
    Group.AutoGranted ->
        stringResource(R.string.setting_page_permissions_group_auto)
}
