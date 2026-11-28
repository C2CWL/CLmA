// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/board/store.ts, server/board/host.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.board

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.botBoardDataStore by preferencesDataStore(name = "bot_team_board")
private val boardKey = stringPreferencesKey("board")

@Serializable
private data class BoardDocument(val tasks: List<BoardTask> = emptyList())

internal class BotBoardStore(context: Context) {
    private val dataStore = context.applicationContext.botBoardDataStore
    private val json = Json { ignoreUnknownKeys = true }
    val tasks = dataStore.data.map { decode(it[boardKey]).tasks }.distinctUntilChanged()

    suspend fun mutate(transform: (List<BoardTask>) -> BoardTask): BoardTask {
        lateinit var changed: BoardTask
        dataStore.edit { preferences ->
            val current = decode(preferences[boardKey])
            changed = transform(current.tasks)
            val next = if (current.tasks.any { it.id == changed.id }) {
                current.tasks.map { if (it.id == changed.id) changed else it }
            } else {
                current.tasks + changed
            }
            preferences[boardKey] = json.encodeToString(BoardDocument(next))
        }
        return changed
    }

    private fun decode(raw: String?): BoardDocument =
        raw?.let { json.decodeFromString<BoardDocument>(it) } ?: BoardDocument()
}

internal fun List<BoardTask>.findBoardTask(id: String): BoardTask =
    requireNotNull(firstOrNull { it.id == id }) { "Task not found." }
