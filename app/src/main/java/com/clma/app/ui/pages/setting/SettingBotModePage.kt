package com.clma.app.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.botmode.botsHiddenForManage
import com.clma.app.data.botmode.withBotHidden
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.pages.chat.botmode.BotModeExtrasSection
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingBotModePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    SettingSubPageScaffold(title = stringResource(R.string.bot_mode_setting_title)) {
        item {
            CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    headlineContent = { Text(stringResource(R.string.bot_mode_setting_title)) },
                    supportingContent = { Text(stringResource(R.string.bot_mode_setting_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.botModeEnabled,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(botModeEnabled = it))
                            },
                        )
                    },
                )
            }
            if (displaySetting.botModeEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BotModeExtrasSection(
                        settings = settings.copy(displaySetting = displaySetting),
                        displaySetting = displaySetting,
                        updateDisplaySetting = ::updateDisplaySetting,
                        updateSettings = { vm.updateSettings(it) },
                    )
                    val hiddenBots = settings.copy(displaySetting = displaySetting).botsHiddenForManage()
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.bot_mode_hidden_manage_title)) },
                    ) {
                        if (hiddenBots.isEmpty()) {
                            item(
                                headlineContent = { Text(stringResource(R.string.bot_mode_hidden_empty)) },
                                supportingContent = { Text(stringResource(R.string.bot_mode_hidden_manage_desc)) },
                            )
                        } else {
                            hiddenBots.forEach { bot ->
                                item(
                                    headlineContent = {
                                        Text(bot.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) })
                                    },
                                    supportingContent = { Text(stringResource(R.string.bot_mode_hidden_manage_desc)) },
                                    trailingContent = {
                                        TextButton(
                                            onClick = {
                                                updateDisplaySetting(displaySetting.withBotHidden(bot.id, false))
                                            },
                                        ) {
                                            Text(stringResource(R.string.bot_mode_hidden_unhide))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
