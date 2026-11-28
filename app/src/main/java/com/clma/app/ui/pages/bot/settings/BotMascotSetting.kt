package com.clma.app.ui.pages.bot.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.model.BotMascotChoice
import com.clma.app.ui.pages.bot.BotMascot
import com.clma.app.ui.pages.bot.BotMascotState

@StringRes
private fun BotMascotChoice.label(): Int = when (this) {
    BotMascotChoice.CAT -> R.string.bot_mascot_cat
    BotMascotChoice.DOG -> R.string.bot_mascot_dog
    BotMascotChoice.NONE -> R.string.bot_mascot_none
}

@Composable
internal fun BotMascotSetting(choice: BotMascotChoice, onSelect: (BotMascotChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.bot_mascot_title), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotMascotChoice.entries.forEach { option ->
                FilterChip(
                    selected = choice == option,
                    onClick = { onSelect(option) },
                    label = { Text(stringResource(option.label())) },
                    leadingIcon = if (option == BotMascotChoice.NONE) null else {
                        { BotMascot(BotMascotState.HAPPY, option, Modifier.size(24.dp)) }
                    },
                )
            }
        }
    }
}
