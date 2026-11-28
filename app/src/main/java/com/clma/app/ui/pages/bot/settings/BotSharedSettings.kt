// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/AboutMeSettings.tsx, src/components/EveryBotRules.tsx,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.clma.app.R
import com.clma.app.data.botmode.settings.BOT_SOUL_MAX_LENGTH
import com.clma.app.data.botmode.settings.BotModeSettings
import com.clma.app.ui.pages.bot.BotCollapsibleGroup

@Composable
internal fun BotSharedSettings(
    settings: BotModeSettings,
    ready: Boolean,
    onUpdate: ((BotModeSettings) -> BotModeSettings) -> Unit,
) {
    BotCollapsibleGroup(key = "shared-context", title = stringResource(R.string.bot_shared_title)) {
        BotSharedField(R.string.bot_shared_about, R.string.bot_shared_about_hint, settings.aboutMe, ready) { value ->
            onUpdate { it.copy(aboutMe = value) }
        }
        BotSharedField(R.string.bot_shared_rules, R.string.bot_shared_rules_hint, settings.everyBotRules, ready) { value ->
            onUpdate { it.copy(everyBotRules = value) }
        }
    }
}

@Composable
private fun BotSharedField(title: Int, description: Int, value: String, ready: Boolean, onSave: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    TextButton(
        onClick = {
            draft = value
            editing = true
        },
        enabled = ready,
    ) { Text(stringResource(title)) }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(title)) },
            text = {
                Column {
                    Text(stringResource(description))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= BOT_SOUL_MAX_LENGTH) draft = it },
                        label = { Text(stringResource(title)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 9,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(draft.trim())
                    editing = false
                }) { Text(stringResource(R.string.bot_config_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.bot_home_cancel)) }
            },
        )
    }
}
