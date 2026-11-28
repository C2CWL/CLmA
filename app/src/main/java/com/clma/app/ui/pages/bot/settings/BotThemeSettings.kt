// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/CustomThemesSettings.tsx, server/themes/store.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.clma.app.R
import com.clma.app.data.botmode.settings.BotModeSettings
import com.clma.app.ui.pages.bot.BotCollapsibleGroup
import com.clma.app.ui.pages.setting.CustomThemeEditSheet
import com.clma.app.ui.theme.CustomTheme

@Composable
internal fun BotThemeSettings(settings: BotModeSettings, update: ((BotModeSettings) -> BotModeSettings) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CustomTheme?>(null) }
    var deleting by remember { mutableStateOf<CustomTheme?>(null) }

    BotCollapsibleGroup(
        key = "bot-themes",
        title = stringResource(R.string.bot_theme_title),
        initiallyExpanded = false,
    ) {
        FilterChip(
            selected = settings.activeThemeId == null,
            onClick = { update { it.copy(activeThemeId = null) } },
            label = { Text(stringResource(R.string.bot_theme_default)) },
        )
        settings.customThemes.forEach { theme ->
            Column(Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = theme.id == settings.activeThemeId,
                    onClick = { update { current ->
                        if (current.customThemes.any { it.id == theme.id }) current.copy(activeThemeId = theme.id)
                        else current
                    } },
                    label = { Text(theme.name) },
                )
                Row {
                    TextButton(onClick = { editing = theme }) { Text(stringResource(R.string.bot_routine_edit)) }
                    TextButton(onClick = { deleting = theme }) { Text(stringResource(R.string.bot_home_menu_delete)) }
                }
            }
        }
        TextButton(onClick = { creating = true }) { Text(stringResource(R.string.bot_theme_create)) }
    }

    if (creating || editing != null) {
        val editedId = editing?.id
        CustomThemeEditSheet(
            theme = editing,
            onDismiss = { editing = null; creating = false },
            onSave = { theme ->
                editing = null
                creating = false
                val saved = theme.copy(name = theme.name.trim().take(60))
                update { current ->
                    if (editedId == null) current.copy(customThemes = current.customThemes + saved)
                    else current.copy(customThemes = current.customThemes.map { if (it.id == editedId) saved else it })
                }
            },
        )
    }

    deleting?.let { theme ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.bot_theme_delete, theme.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    update { current ->
                        current.copy(
                            customThemes = current.customThemes.filterNot { it.id == theme.id },
                            activeThemeId = current.activeThemeId?.takeUnless { it == theme.id },
                        )
                    }
                }) { Text(stringResource(R.string.bot_home_menu_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.bot_home_cancel)) }
            },
        )
    }
}
