// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), docs/announcements.md, src/components/AnnouncementCard.tsx, src/components/whats-new/, server/routes/announcements.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.BuildConfig
import com.clma.app.R
import com.clma.app.data.botmode.settings.BotModeSettings
import com.clma.app.data.botmode.settings.BotModeSettingsStore
import com.clma.app.ui.pages.bot.BotCollapsibleGroup
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private data class LocalAnnouncement(val id: String, val title: Int, val body: Int)

private val announcements = listOf(
    LocalAnnouncement("bot-configuration", R.string.bot_notices_config_title, R.string.bot_notices_config_body),
)

@Composable
internal fun BotHomeAnnouncement() {
    val store = koinInject<BotModeSettingsStore>()
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val save = rememberBotSettingsSaver()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val current = settings?.takeIf { it.showAnnouncementCards }
    val announcement = current?.let { state -> announcements.firstOrNull { it.id !in state.dismissedAnnouncementIds } }
    if (announcement != null) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(announcement.title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(announcement.body), style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = { showHistory = true }) { Text(stringResource(R.string.bot_notices_history)) }
                    TextButton(onClick = {
                        save { store.update { it.copy(dismissedAnnouncementIds = it.dismissedAnnouncementIds + announcement.id) } }
                    }) { Text(stringResource(R.string.bot_notices_dismiss)) }
                }
            }
        }
    }
    if (showHistory) BotAnnouncementHistory { showHistory = false }
}

@Composable
internal fun BotAnnouncementsSettings(
    settings: BotModeSettings,
    onUpdate: ((BotModeSettings) -> BotModeSettings) -> Unit,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    BotCollapsibleGroup(key = "announcements", title = stringResource(R.string.bot_notices_title)) {
        BotSwitchRow(R.string.bot_notices_show_cards, settings.showAnnouncementCards) { enabled ->
            onUpdate { it.copy(showAnnouncementCards = enabled) }
        }
        TextButton(onClick = { showHistory = true }) { Text(stringResource(R.string.bot_notices_history)) }
    }
    if (showHistory) BotAnnouncementHistory { showHistory = false }
}

@Composable
private fun BotAnnouncementHistory(onClose: () -> Unit) {
    val assets = LocalContext.current.assets
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val versions by produceState<List<String>?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            try {
                assets.list("changelog").orEmpty().mapNotNull { file ->
                    file.takeIf { it.matches(Regex("""[0-9]+(?:\.[0-9]+)*\.txt""")) }?.removeSuffix(".txt")
                }.sortedWith { left, right -> compareVersions(right, left) }
            } catch (_: IOException) {
                emptyList()
            }
        }
    }
    val version = selected
    val notes by produceState<String?>(null, assets, version) {
        value = null
        if (version != null) {
            value = withContext(Dispatchers.IO) {
                try {
                    assets.open("changelog/$version.txt").bufferedReader().use { it.readText() }
                } catch (_: IOException) {
                    ""
                }
            }
        }
    }
    val close = { if (version == null) onClose() else selected = null }
    AlertDialog(
        onDismissRequest = close,
        title = {
            Text(
                if (version == null) stringResource(R.string.bot_notices_title)
                else stringResource(R.string.changelog_title, version),
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (version == null) {
                    announcements.forEach { announcement ->
                        Text(stringResource(announcement.title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(announcement.body))
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.bot_notices_history), style = MaterialTheme.typography.titleSmall)
                    val releases = versions
                    when {
                        releases == null -> Text(stringResource(R.string.changelog_loading))
                        releases.isEmpty() -> Text(stringResource(R.string.changelog_unavailable))
                        else -> releases.forEach { release ->
                            TextButton(onClick = { selected = release }) {
                                Text(
                                    if (release == BuildConfig.VERSION_NAME) stringResource(R.string.bot_notices_current, release)
                                    else stringResource(R.string.changelog_title, release),
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        when {
                            notes == null -> stringResource(R.string.changelog_loading)
                            notes.isNullOrBlank() -> stringResource(R.string.changelog_unavailable)
                            else -> notes.orEmpty()
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = close) {
                Text(stringResource(if (version == null) R.string.changelog_confirm else R.string.back))
            }
        },
    )
}

private fun compareVersions(left: String, right: String): Int {
    val l = left.split('.')
    val r = right.split('.')
    for (index in 0 until maxOf(l.size, r.size)) {
        val comparison = (l.getOrNull(index)?.toIntOrNull() ?: 0).compareTo(r.getOrNull(index)?.toIntOrNull() ?: 0)
        if (comparison != 0) return comparison
    }
    return left.compareTo(right)
}
