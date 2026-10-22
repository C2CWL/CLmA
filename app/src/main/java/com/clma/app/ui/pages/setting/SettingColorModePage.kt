package com.clma.app.ui.pages.setting

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.Select
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.hooks.rememberAmoledDarkMode
import com.clma.app.ui.hooks.rememberColorMode
import com.clma.app.ui.theme.ColorMode

/** 深浅色模式: colour mode and AMOLED black, moved out of the theme page. */
@Composable
fun SettingColorModePage() {
    var amoledDarkMode by rememberAmoledDarkMode()
    var colorMode by rememberColorMode()
    val navController = LocalNavController.current

    SettingSubPageScaffold(title = stringResource(R.string.setting_hub_color_mode)) {
        item {
            CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_color_mode)) },
                    supportingContent = { Text(colorModeLabel(colorMode)) },
                    trailingContent = {
                        Select(
                            options = ColorMode.entries,
                            selectedOption = colorMode,
                            onOptionSelected = {
                                colorMode = it
                                // Re-open this page so it is redrawn with the new mode at once
                                // (same trick the theme page used).
                                navController.navigate(Screen.SettingColorMode) {
                                    popUpTo(Screen.SettingColorMode) { inclusive = true }
                                }
                            },
                            optionToString = { colorModeLabel(it) },
                            modifier = Modifier.width(150.dp),
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc)) },
                    trailingContent = {
                        Switch(
                            checked = amoledDarkMode,
                            onCheckedChange = { amoledDarkMode = it },
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun colorModeLabel(mode: ColorMode): String = when (mode) {
    ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
    ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
    ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
}
