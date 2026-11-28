// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/PlacesRail.tsx, src/components/PlaceAddSheet.tsx, src/components/PlaceLookCard.tsx
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.botmode.BotPlace
import com.clma.app.data.botmode.BotPlaceColor
import com.clma.app.data.botmode.botAssistants
import com.clma.app.data.botmode.isBotPlaceName
import com.clma.app.data.datastore.Settings
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import kotlin.uuid.Uuid

@Composable
internal fun BotPlacesNavigation(
    places: List<BotPlace>,
    selected: BotPlace?,
    onSelect: (BotPlace?) -> Unit,
    onAdd: () -> Unit,
    onEdit: (BotPlace) -> Unit,
    enabled: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = selected == null,
                    onClick = { onSelect(null) },
                    label = { Text(stringResource(R.string.bot_places_all)) },
                )
            }
            items(places, key = { it.id.toString() }) { place ->
                FilterChip(
                    selected = selected?.id == place.id,
                    onClick = { onSelect(place) },
                    leadingIcon = { PlaceColorDot(place.color) },
                    label = { Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                Icon(Lucide.EllipsisVertical, contentDescription = stringResource(R.string.bot_places_menu))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bot_places_add)) },
                    onClick = {
                        menuOpen = false
                        onAdd()
                    },
                )
                if (selected != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bot_places_edit)) },
                        onClick = {
                            menuOpen = false
                            onEdit(selected)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun BotPlaceEditor(
    draftId: Uuid,
    initial: BotPlace?,
    settings: Settings,
    onDismiss: () -> Unit,
    onSave: (BotPlace, (Boolean) -> Unit) -> Unit,
    onRemove: (Uuid) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember { mutableStateOf(initial?.color ?: BotPlaceColor.DEFAULT) }
    var botIds by remember { mutableStateOf(initial?.botIds.orEmpty()) }
    var channelIds by remember { mutableStateOf(initial?.channelIds.orEmpty()) }
    var pending by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    val places = settings.displaySetting.botPlaces
    val duplicate = places.any { it.id != draftId && it.name.equals(name.trim(), ignoreCase = true) }
    val valid = name.isBotPlaceName()
    val bots = settings.botAssistants()
    val channels = settings.displaySetting.botChannels

    if (confirmRemove && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.bot_places_remove)) },
            text = { Text(stringResource(R.string.bot_places_remove_confirm, initial.name)) },
            confirmButton = {
                TextButton(onClick = { onRemove(initial.id) }) {
                    Text(stringResource(R.string.bot_places_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.bot_home_cancel)) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!pending) onDismiss() },
        title = { Text(stringResource(if (initial == null) R.string.bot_places_add else R.string.bot_places_edit)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            saveFailed = false
                        },
                        label = { Text(stringResource(R.string.bot_places_name)) },
                        singleLine = true,
                        enabled = !pending,
                        isError = duplicate || (name.isNotEmpty() && !valid),
                        supportingText = when {
                            duplicate -> ({ Text(stringResource(R.string.bot_places_duplicate)) })
                            name.isNotEmpty() && !valid -> ({ Text(stringResource(R.string.bot_places_name_rule)) })
                            else -> null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (initial == null) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf(R.string.bot_places_work, R.string.bot_places_personal, R.string.bot_places_project)) { label ->
                                val suggestion = stringResource(label)
                                FilterChip(
                                    selected = name == suggestion,
                                    enabled = !pending,
                                    onClick = { name = suggestion },
                                    label = { Text(suggestion) },
                                )
                            }
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.bot_places_color), style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(BotPlaceColor.entries) { option ->
                            FilterChip(
                                selected = option == color,
                                enabled = !pending,
                                onClick = { color = option },
                                leadingIcon = { PlaceColorDot(option) },
                                label = { Text(stringResource(option.label())) },
                            )
                        }
                    }
                }
                if (bots.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.bot_home_tab_bots), style = MaterialTheme.typography.titleSmall)
                    }
                    items(bots, key = { "bot-${it.id}" }) { bot ->
                        PlaceMemberRow(
                            name = bot.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                            otherPlace = places.firstOrNull { it.id != draftId && bot.id in it.botIds }?.name,
                            checked = bot.id in botIds,
                            enabled = !pending,
                            onToggle = { checked -> botIds = if (checked) botIds + bot.id else botIds - bot.id },
                        )
                    }
                }
                if (channels.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.bot_home_tab_channels), style = MaterialTheme.typography.titleSmall)
                    }
                    items(channels, key = { "channel-${it.id}" }) { channel ->
                        PlaceMemberRow(
                            name = channel.name.ifBlank { stringResource(R.string.bot_channel_untitled) },
                            otherPlace = places.firstOrNull { it.id != draftId && channel.id in it.channelIds }?.name,
                            checked = channel.id in channelIds,
                            enabled = !pending,
                            onToggle = { checked -> channelIds = if (checked) channelIds + channel.id else channelIds - channel.id },
                        )
                    }
                }
                if (saveFailed) {
                    item { Text(stringResource(R.string.bot_places_save_failed), color = MaterialTheme.colorScheme.error) }
                }
                if (initial != null) {
                    item {
                        TextButton(onClick = { confirmRemove = true }, enabled = !pending) {
                            Text(stringResource(R.string.bot_places_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !duplicate && !pending,
                onClick = {
                    pending = true
                    onSave(BotPlace(draftId, name.trim(), color, botIds, channelIds)) { saved ->
                        pending = false
                        saveFailed = !saved
                    }
                },
            ) { Text(stringResource(if (initial == null) R.string.bot_places_create else R.string.bot_places_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !pending) { Text(stringResource(R.string.bot_home_cancel)) }
        },
    )
}

@Composable
private fun PlaceMemberRow(
    name: String,
    otherPlace: String?,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Column {
            Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!checked && otherPlace != null) {
                Text(otherPlace, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PlaceColorDot(color: BotPlaceColor) {
    val tint = when (color) {
        BotPlaceColor.DEFAULT -> MaterialTheme.colorScheme.outline
        BotPlaceColor.BLUE -> Color(0xFF5589D5)
        BotPlaceColor.GREEN -> Color(0xFF378761)
        BotPlaceColor.PURPLE -> Color(0xFF9767CE)
        BotPlaceColor.ORANGE -> Color(0xFFC27330)
    }
    Box(Modifier.size(12.dp).background(tint, CircleShape))
}

private fun BotPlaceColor.label() = when (this) {
    BotPlaceColor.DEFAULT -> R.string.bot_places_color_default
    BotPlaceColor.BLUE -> R.string.bot_places_color_blue
    BotPlaceColor.GREEN -> R.string.bot_places_color_green
    BotPlaceColor.PURPLE -> R.string.bot_places_color_purple
    BotPlaceColor.ORANGE -> R.string.bot_places_color_orange
}
