// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/locales/en.json, src/locales/zh.json,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.AppScope
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.data.botmode.botsHiddenForManage
import com.clma.app.data.botmode.withBotHidden
import com.clma.app.data.botmode.settings.BotActivity
import com.clma.app.data.botmode.settings.BotApprovalLevel
import com.clma.app.data.botmode.settings.BotModeSettings
import com.clma.app.data.botmode.settings.BotModeSettingsStore
import com.clma.app.data.botmode.settings.BotOutboundPolicy
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.pages.bot.BotCollapsibleGroup
import com.clma.app.ui.pages.chat.botmode.BotModeExtrasSection
import com.clma.app.ui.pages.setting.SettingVM
import java.io.IOException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotSettingsPage(vm: SettingVM = koinViewModel()) {
    val nav = LocalNavController.current
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val store = koinInject<BotModeSettingsStore>()
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = BotModeSettings())
    val save = rememberBotSettingsSaver()
    fun update(transform: (BotModeSettings) -> BotModeSettings) = save { store.update(transform) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bot_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BotCollapsibleGroup(key = "defaults", title = stringResource(R.string.bot_settings_defaults)) {
                BotChoiceRow(
                    stringResource(R.string.bot_settings_approval),
                    approvalLabel(settings.approvalLevel),
                    BotApprovalLevel.entries.map { it to approvalLabel(it) },
                    confirmation = { if (it == BotApprovalLevel.FULL) R.string.bot_settings_full_confirm_default else null },
                ) { level -> update { it.copy(approvalLevel = level) } }
                BotChoiceRow(
                    stringResource(R.string.bot_settings_outbound),
                    outboundLabel(settings.outbound),
                    BotOutboundPolicy.entries.map { it to outboundLabel(it) },
                ) { outbound -> update { it.copy(outbound = outbound) } }
            }
            TextButton(
                onClick = { nav.navigate(Screen.BotFeedback) },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text(stringResource(R.string.bot_feedback_title))
            }
            BotCollapsibleGroup(key = "routines", title = stringResource(R.string.bot_settings_routines)) {
                BotSwitchRow(R.string.bot_settings_reliability, settings.showRoutineReliability) { enabled ->
                    update { it.copy(showRoutineReliability = enabled) }
                }
            }
            BotModeExtrasSection(
                settings = appSettings,
                displaySetting = appSettings.displaySetting,
                updateDisplaySetting = { vm.updateSettings(appSettings.copy(displaySetting = it)) },
                updateSettings = { vm.updateSettings(it) },
            )
            BotCollapsibleGroup(
                key = "hidden",
                title = stringResource(R.string.bot_mode_hidden_manage_title),
                initiallyExpanded = false,
            ) {
                val hidden = appSettings.botsHiddenForManage()
                if (hidden.isEmpty()) Text(stringResource(R.string.bot_mode_hidden_empty))
                hidden.forEach { bot ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            bot.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                            Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            vm.updateSettings { current ->
                                current.copy(displaySetting = current.displaySetting.withBotHidden(bot.id, false))
                            }
                        }) { Text(stringResource(R.string.bot_mode_hidden_unhide)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun approvalLabel(level: BotApprovalLevel): String = stringResource(
    when (level) {
        BotApprovalLevel.ASK -> R.string.bot_settings_ask
        BotApprovalLevel.AUTO -> R.string.bot_settings_auto
        BotApprovalLevel.FULL -> R.string.bot_settings_full
    },
)

@Composable
internal fun outboundLabel(policy: BotOutboundPolicy): String = stringResource(
    when (policy) {
        BotOutboundPolicy.ASK -> R.string.bot_settings_outbound_ask
        BotOutboundPolicy.ALLOW -> R.string.bot_settings_outbound_allow
    },
)

@Composable
internal fun BotActivityLabel(activity: BotActivity) {
    val label = when (activity) {
        BotActivity.WORKING -> R.string.bot_settings_working
        BotActivity.WAITING -> R.string.bot_settings_waiting
        BotActivity.IDLE -> R.string.bot_settings_idle
    }
    val color = when (activity) {
        BotActivity.WORKING -> MaterialTheme.colorScheme.primary
        BotActivity.WAITING -> MaterialTheme.colorScheme.tertiary
        BotActivity.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
internal fun rememberBotSettingsSaver(): (suspend () -> Unit) -> Unit {
    val scope = koinInject<AppScope>()
    val context = LocalContext.current.applicationContext
    return { action ->
        scope.launch {
            try {
                action()
            } catch (_: IOException) {
                Toast.makeText(context, R.string.bot_settings_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
internal fun <T> BotChoiceRow(
    title: String,
    value: String,
    options: List<Pair<T, String>>,
    confirmation: (T) -> Int? = { null },
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<T, Int>?>(null) }
    Box {
        BotValueRow(title, value) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        val message = confirmation(option)
                        if (message == null) onSelect(option) else pending = option to message
                    },
                )
            }
        }
    }
    pending?.let { (option, message) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.bot_settings_full)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onSelect(option)
                }) { Text(stringResource(R.string.bot_settings_full)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.bot_home_cancel)) }
            },
        )
    }
}

@Composable
internal fun BotSwitchRow(title: Int, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun BotValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}
