// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/bot-settings/sections.ts, src/locales/en.json, src/locales/zh.json,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.botmode.settings.BOT_ALWAYS_ALLOW_MAX
import com.clma.app.data.botmode.settings.BOT_DESCRIPTION_MAX_LENGTH
import com.clma.app.data.botmode.settings.BOT_SOUL_MAX_LENGTH
import com.clma.app.data.botmode.settings.BotApprovalLevel
import com.clma.app.data.botmode.settings.BotModeSettings
import com.clma.app.data.botmode.settings.BotModeSettingsStore
import com.clma.app.data.botmode.settings.BotOutboundPolicy
import com.clma.app.data.botmode.settings.BotProfile
import com.clma.app.data.botmode.settings.BotProfileStore
import com.clma.app.data.model.Assistant
import com.clma.app.ui.pages.bot.BotCollapsibleGroup
import org.koin.compose.koinInject

@Composable
fun BotProfileSettings(bot: Assistant, bots: List<Assistant>) {
    val store = koinInject<BotProfileStore>()
    val defaults = koinInject<BotModeSettingsStore>()
    val profiles by store.profiles.collectAsStateWithLifecycle(initialValue = emptyMap())
    val settings by defaults.settings.collectAsStateWithLifecycle(initialValue = BotModeSettings())
    val save = rememberBotSettingsSaver()
    val id = bot.id.toString()
    val profile = profiles[id] ?: BotProfile()
    fun update(transform: (BotProfile) -> BotProfile) = save { store.update(id, transform) }

    BotCollapsibleGroup(key = "soul", title = stringResource(R.string.bot_settings_soul), initiallyExpanded = false) {
        BotProfileTextEditor(
            current = profile.soul,
            label = R.string.bot_settings_standing_instructions,
            maxLength = BOT_SOUL_MAX_LENGTH,
        ) { text -> update { it.copy(soul = text) } }
    }
    BotCollapsibleGroup(key = "access", title = stringResource(R.string.bot_settings_access), initiallyExpanded = false) {
        Text(
            stringResource(R.string.bot_settings_allow_count, profile.alwaysAllow.size, BOT_ALWAYS_ALLOW_MAX),
            style = MaterialTheme.typography.bodySmall,
        )
        profile.alwaysAllow.forEach { tool ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tool, Modifier.weight(1f))
                TextButton(onClick = { update { it.copy(alwaysAllow = it.alwaysAllow - tool) } }) {
                    Text(stringResource(R.string.bot_settings_remove))
                }
            }
        }
    }
    BotCollapsibleGroup(key = "permissions", title = stringResource(R.string.bot_settings_permissions)) {
        BotApprovalRow(profile.approvalLevel, settings.approvalLevel) { level ->
            update { it.copy(approvalLevel = level) }
        }
        val defaultOutbound = stringResource(R.string.bot_settings_use_default, outboundLabel(settings.outbound))
        BotChoiceRow(
            stringResource(R.string.bot_settings_outbound),
            profile.outbound?.let { outboundLabel(it) } ?: defaultOutbound,
            listOf(null to defaultOutbound) + BotOutboundPolicy.entries.map { it to outboundLabel(it) },
        ) { outbound -> update { it.copy(outbound = outbound) } }
        BotSwitchRow(R.string.bot_settings_chief, profile.chiefOfStaff) { chief ->
            update { it.copy(chiefOfStaff = chief) }
        }
        val none = stringResource(R.string.bot_settings_none)
        val teammates = bots.filter { it.id != bot.id }.map { it.id.toString() to it.name }
        BotChoiceRow(
            stringResource(R.string.bot_settings_reports_to),
            teammates.firstOrNull { it.first == profile.reportsTo }?.second ?: none,
            listOf(null to none) + teammates,
        ) { reportsTo -> update { it.copy(reportsTo = reportsTo) } }
    }
}

@Composable
fun BotDescriptionSettings(botId: String) {
    val store = koinInject<BotProfileStore>()
    val profiles by store.profiles.collectAsStateWithLifecycle(initialValue = emptyMap())
    val save = rememberBotSettingsSaver()
    BotProfileTextEditor(
        current = profiles[botId]?.description.orEmpty(),
        label = R.string.bot_settings_description,
        maxLength = BOT_DESCRIPTION_MAX_LENGTH,
    ) { text -> save { store.update(botId) { it.copy(description = text) } } }
}

@Composable
private fun BotProfileTextEditor(current: String, label: Int, maxLength: Int, onSave: (String) -> Unit) {
    var draft by rememberSaveable(current) { mutableStateOf(current) }
    OutlinedTextField(
        value = draft,
        onValueChange = { if (it.length <= maxLength) draft = it },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 10,
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(R.string.bot_settings_character_count, draft.length, maxLength)) },
    )
    TextButton(enabled = draft != current, onClick = { onSave(draft) }) {
        Text(stringResource(R.string.bot_settings_save))
    }
}

@Composable
private fun BotApprovalRow(
    level: BotApprovalLevel?,
    default: BotApprovalLevel,
    onSelect: (BotApprovalLevel?) -> Unit,
) {
    val defaultLabel = stringResource(R.string.bot_settings_use_default, approvalLabel(default))
    BotChoiceRow(
        stringResource(R.string.bot_settings_approval),
        level?.let { approvalLabel(it) } ?: defaultLabel,
        listOf(null to defaultLabel) + BotApprovalLevel.entries.map { it to approvalLabel(it) },
        confirmation = { if (it == BotApprovalLevel.FULL) R.string.bot_settings_full_confirm else null },
        onSelect = onSelect,
    )
}
