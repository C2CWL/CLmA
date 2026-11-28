// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), docs/pausing-bots.md, docs/usage-caps.md, src/locales/zh.json,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.ai.provider.ProviderSetting
import com.clma.app.R
import com.clma.app.data.botmode.BotUsageCaps
import com.clma.app.data.botmode.settings.BotModeSettings
import com.clma.app.data.botmode.settings.BotModeSettingsStore
import com.clma.app.data.botmode.settings.BotProfile
import com.clma.app.data.botmode.settings.BotProfileStore
import com.clma.app.data.datastore.findModelById
import com.clma.app.service.ChatService
import com.clma.app.ui.pages.bot.BotCollapsibleGroup
import com.clma.app.ui.pages.chat.botmode.BotModelPickerButton
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

@Composable
internal fun BotAvailabilitySettings(botId: Uuid, providers: List<ProviderSetting>) {
    val chatService = koinInject<ChatService>()
    val store = koinInject<BotProfileStore>()
    val defaults = koinInject<BotModeSettingsStore>()
    val settings by defaults.settings.collectAsStateWithLifecycle(initialValue = BotModeSettings())
    val profiles by store.profiles.collectAsStateWithLifecycle(initialValue = emptyMap())
    val profile = profiles[botId.toString()] ?: BotProfile()
    val save = rememberBotSettingsSaver()
    val pausedLabel = stringResource(R.string.bot_quota_paused)
    BotCollapsibleGroup(
        key = "bot_availability",
        title = stringResource(R.string.bot_quota_availability),
        summary = pausedLabel.takeIf { profile.paused || settings.allBotsPaused },
        initiallyExpanded = false,
    ) {
        BotSwitchRow(R.string.bot_quota_pause_bot, profile.paused) { paused ->
            save { chatService.setBotPaused(botId, paused) }
        }
        BotSwitchRow(R.string.bot_quota_pause_all, settings.allBotsPaused) { paused ->
            save { chatService.setAllBotsPaused(paused) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bot_quota_backup), Modifier.weight(1f))
            val backup = profile.backupModelId?.let { providers.findModelById(it) }
            BotModelPickerButton(
                modelId = profile.backupModelId,
                modelLabel = backup?.displayName ?: stringResource(R.string.bot_quota_choose_backup),
                providers = providers.filter { it.enabled },
                onSelect = { model ->
                    save { store.update(botId.toString()) { it.copy(backupModelId = model.id) } }
                },
            )
        }
        if (profile.backupModelId != null) {
            TextButton(onClick = { save { store.update(botId.toString()) { it.copy(backupModelId = null) } } }) {
                Text(stringResource(R.string.bot_quota_clear_backup))
            }
        }
    }
    BotCollapsibleGroup(
        key = "bot_usage_caps",
        title = stringResource(R.string.bot_quota_usage_caps),
        initiallyExpanded = false,
    ) {
        BotUsageCapsEditor(profile.usageCaps) { caps ->
            save { store.update(botId.toString()) { it.copy(usageCaps = caps) } }
        }
    }
}

@Composable
private fun BotUsageCapsEditor(caps: BotUsageCaps, onSave: (BotUsageCaps) -> Unit) {
    var dailyTokens by rememberSaveable(caps.dailyTokens) { mutableStateOf(caps.dailyTokens.toString()) }
    var monthlyTokens by rememberSaveable(caps.monthlyTokens) { mutableStateOf(caps.monthlyTokens.toString()) }
    var dailyUsd by rememberSaveable(caps.dailyUsd) { mutableStateOf(caps.dailyUsd.toString()) }
    var monthlyUsd by rememberSaveable(caps.monthlyUsd) { mutableStateOf(caps.monthlyUsd.toString()) }
    var stopsInteractive by rememberSaveable(caps.stopsInteractive) { mutableStateOf(caps.stopsInteractive) }

    val dailyTokenCap = dailyTokens.ifBlank { "0" }.toLongOrNull()?.takeIf { it >= 0 }
    val monthlyTokenCap = monthlyTokens.ifBlank { "0" }.toLongOrNull()?.takeIf { it >= 0 }
    val dailyCostCap = dailyUsd.replace(',', '.').ifBlank { "0" }.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    val monthlyCostCap = monthlyUsd.replace(',', '.').ifBlank { "0" }.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    val updated = if (dailyTokenCap != null && monthlyTokenCap != null && dailyCostCap != null && monthlyCostCap != null) {
        BotUsageCaps(dailyTokenCap, monthlyTokenCap, dailyCostCap, monthlyCostCap, stopsInteractive)
    } else null

    Column {
        Text(stringResource(R.string.bot_quota_cap_hint))
        CapField(R.string.bot_quota_daily_tokens, dailyTokens, dailyTokenCap != null, KeyboardType.Number) { dailyTokens = it }
        CapField(R.string.bot_quota_monthly_tokens, monthlyTokens, monthlyTokenCap != null, KeyboardType.Number) { monthlyTokens = it }
        CapField(R.string.bot_quota_daily_usd, dailyUsd, dailyCostCap != null, KeyboardType.Decimal) { dailyUsd = it }
        CapField(R.string.bot_quota_monthly_usd, monthlyUsd, monthlyCostCap != null, KeyboardType.Decimal) { monthlyUsd = it }
        BotSwitchRow(R.string.bot_quota_cap_interactive, stopsInteractive) { stopsInteractive = it }
        TextButton(enabled = updated != null && updated != caps, onClick = { updated?.let(onSave) }) {
            Text(stringResource(R.string.bot_settings_save))
        }
    }
}

@Composable
private fun CapField(label: Int, value: String, valid: Boolean, keyboard: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}
