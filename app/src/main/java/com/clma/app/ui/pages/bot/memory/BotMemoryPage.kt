// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/bot-settings/MemorySection.tsx, src/locales/en.json, src/locales/zh.json,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.botmode.memory.BotMemoryCaps
import com.clma.app.data.botmode.memory.BotMemoryOrganizer
import com.clma.app.data.botmode.memory.BotMemoryStore
import com.clma.app.data.botmode.memory.BotMemoryWorker
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.model.isBot
import com.clma.app.ui.context.LocalNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date
import kotlin.uuid.Uuid

private data class MemoryOverview(
    val index: String,
    val topics: List<String>,
    val logs: List<String>,
    val automatic: Boolean,
    val lastOrganized: Long?,
)

private fun BotMemoryStore.overview() = MemoryOverview(index(), topics(), logs(), upkeepEnabled(), lastOrganized())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotMemoryPage(botId: String) {
    val context = LocalContext.current.applicationContext
    val nav = LocalNavController.current
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val id = remember(botId) { Uuid.parse(botId) }
    val bot = settings.assistants.firstOrNull { it.id == id && it.isBot } ?: return
    val store = remember(botId) { BotMemoryStore(context.filesDir, botId) }
    val scope = rememberCoroutineScope()
    var overview by remember(botId) { mutableStateOf<MemoryOverview?>(null) }
    var draft by rememberSaveable(botId) { mutableStateOf<String?>(null) }
    var baseline by rememberSaveable(botId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var opened by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteTopic by remember { mutableStateOf<String?>(null) }

    suspend fun refresh(resetDraft: Boolean) {
        val loaded = withContext(Dispatchers.IO) { store.overview() }
        overview = loaded
        if (resetDraft || draft == null) {
            baseline = loaded.index
            draft = loaded.index
        }
    }

    fun perform(action: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = R.string.bot_memory_error
            } finally {
                busy = false
                organizing = false
            }
        }
    }

    LaunchedEffect(botId) { perform { refresh(false) } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.bot_memory_title)) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
            },
        )
    }) { padding ->
        val info = overview
        val text = draft.orEmpty()
        val dirty = draft != baseline
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bot_memory_enabled), Modifier.weight(1f))
                    Switch(checked = bot.enableMemory, enabled = !busy, onCheckedChange = { enabled ->
                        perform {
                            settingsStore.update { s ->
                                s.copy(assistants = s.assistants.map {
                                    if (it.id == id && it.isBot) it.copy(enableMemory = enabled) else it
                                })
                            }
                        }
                    })
                }
            }
            if (info == null) {
                item {
                    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    TextButton(enabled = !busy, onClick = { perform { refresh(false) } }) {
                        Text(stringResource(R.string.bot_memory_reload))
                    }
                }
                return@LazyColumn
            }
            item {
                Text("MEMORY.md", style = MaterialTheme.typography.titleMedium)
                val bytes = remember(text) { BotMemoryCaps.bytes(text) }
                val lines = remember(text) { if (text.isEmpty()) 0 else text.trimEnd('\n').lines().size }
                Text(stringResource(R.string.bot_memory_capacity, lines, BotMemoryCaps.INDEX_LINES, bytes, BotMemoryCaps.INDEX_BYTES))
                OutlinedTextField(
                    value = text,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    enabled = !busy,
                    minLines = 5,
                    placeholder = { Text(stringResource(R.string.bot_memory_placeholder)) },
                )
                Row {
                    TextButton(enabled = dirty && !busy, onClick = {
                        perform {
                            val expected = checkNotNull(baseline)
                            when {
                                BotMemoryCaps.bytes(text) > BotMemoryCaps.FILE_BYTES -> error = R.string.bot_memory_too_large
                                withContext(Dispatchers.IO) {
                                    try {
                                        store.saveIndex(text, expected)
                                        false
                                    } catch (_: IllegalStateException) {
                                        true
                                    }
                                } -> error = R.string.bot_memory_conflict
                                else -> refresh(true)
                            }
                        }
                    }) { Text(stringResource(R.string.bot_memory_save)) }
                    TextButton(enabled = !busy, onClick = { perform { refresh(true) } }) {
                        Text(stringResource(R.string.bot_memory_reload))
                    }
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                Text(stringResource(R.string.bot_memory_topics), style = MaterialTheme.typography.titleMedium)
                if (info.topics.isEmpty()) Text(stringResource(R.string.bot_memory_empty))
            }
            items(info.topics, key = { "topic/$it" }) { name ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(modifier = Modifier.weight(1f), enabled = !busy, onClick = {
                        perform { opened = "$name.md" to withContext(Dispatchers.IO) { store.readTopic(name) } }
                    }) { Text("$name.md") }
                    TextButton(enabled = !busy, onClick = { deleteTopic = name }) {
                        Text(stringResource(R.string.bot_memory_delete))
                    }
                }
            }
            item {
                HorizontalDivider()
                Text(stringResource(R.string.bot_memory_logs), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.bot_memory_recent_hint))
                if (info.logs.isEmpty()) Text(stringResource(R.string.bot_memory_empty))
            }
            items(info.logs, key = { "log/$it" }) { day ->
                TextButton(enabled = !busy, onClick = {
                    perform { opened = day to withContext(Dispatchers.IO) { store.readLog(day) } }
                }) { Text(day) }
            }
            item {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.bot_memory_auto))
                        Text(stringResource(R.string.bot_memory_auto_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = info.automatic, enabled = !busy, onCheckedChange = { enabled ->
                        perform {
                            withContext(Dispatchers.IO) {
                                store.setUpkeepEnabled(enabled)
                                BotMemoryWorker.schedule(context, id, enabled)
                            }
                            refresh(false)
                        }
                    })
                }
                Text(info.lastOrganized?.let {
                    stringResource(R.string.bot_memory_last, DateFormat.getDateTimeInstance().format(Date(it)))
                } ?: stringResource(R.string.bot_memory_never))
                TextButton(enabled = !busy && !dirty, onClick = {
                    organizing = true
                    perform {
                        BotMemoryOrganizer.run(context, id)
                        refresh(true)
                    }
                }) {
                    Text(stringResource(if (organizing) R.string.bot_memory_organizing else R.string.bot_memory_organize))
                }
            }
        }
    }

    opened?.let { (name, content) ->
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text(name) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    items(content.lines()) { Text(it.ifEmpty { " " }) }
                }
            },
            confirmButton = {
                TextButton(onClick = { opened = null }) { Text(stringResource(R.string.back)) }
            },
        )
    }
    deleteTopic?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTopic = null },
            title = { Text(stringResource(R.string.bot_memory_confirm_delete, "$name.md")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTopic = null
                    perform {
                        withContext(Dispatchers.IO) { store.deleteTopic(name) }
                        refresh(false)
                    }
                }) { Text(stringResource(R.string.bot_memory_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTopic = null }) { Text(stringResource(R.string.bot_home_cancel)) }
            },
        )
    }
}
