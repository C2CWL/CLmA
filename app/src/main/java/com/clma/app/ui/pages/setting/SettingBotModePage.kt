package com.clma.app.ui.pages.setting

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.components.ui.CardGroup
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingBotModePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val displaySetting = settings.displaySetting

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
                                vm.updateSettings { current ->
                                    current.copy(displaySetting = current.displaySetting.copy(botModeEnabled = it))
                                }
                            },
                        )
                    },
                )
            }
            if (displaySetting.botModeEnabled) {
                val nav = LocalNavController.current
                TextButton(onClick = { nav.navigate(Screen.BotSettings) }) {
                    Text(stringResource(R.string.bot_settings_title))
                }
            }
        }
    }
}
