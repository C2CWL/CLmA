package com.clma.app.ui.pages.bot.routine

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.clma.app.R

@Composable
internal fun RoutineBackupModel(models: List<Pair<String, String>>, selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(stringResource(R.string.routine2_backup_model), style = MaterialTheme.typography.labelLarge)
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(models.firstOrNull { it.first == selected }?.second ?: stringResource(R.string.routine2_none))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.routine2_none)) },
                onClick = { onSelect(null); expanded = false },
            )
            models.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(id); expanded = false },
                )
            }
        }
    }
}
