// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/host/check-backs.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal data class CalendarCheckBack(
    val id: String,
    val assistantId: String,
    val conversationId: String,
    val atMs: Long,
    val note: String,
)

@Serializable
private data class CheckBackSnapshot(val checkBacks: Map<String, CalendarCheckBack> = emptyMap())

internal data class BotCalendarSnapshot(
    val nowMs: Long = System.currentTimeMillis(),
    val checkBacks: List<CalendarCheckBack> = emptyList(),
    val checkBackReadFailed: Boolean = false,
)

private val checkBackJson = Json { ignoreUnknownKeys = true }

internal fun observeBotCalendar(filesDir: File) = flow {
    // The routine store atomically replaces this file. Read only the committed base file
    // so that observing it cannot interfere with AtomicFile's in-progress writes.
    val file = File(filesDir, "botmode/routines-v2.json")
    while (true) {
        val now = System.currentTimeMillis()
        val snapshot = try {
            val checkBacks = checkBackJson.decodeFromString<CheckBackSnapshot>(file.readText())
            BotCalendarSnapshot(now, checkBacks.checkBacks.values.toList())
        } catch (_: FileNotFoundException) {
            BotCalendarSnapshot(now)
        } catch (_: IOException) {
            BotCalendarSnapshot(now, checkBackReadFailed = true)
        } catch (_: SerializationException) {
            BotCalendarSnapshot(now, checkBackReadFailed = true)
        }
        emit(snapshot)
        delay(5_000)
    }
}.flowOn(Dispatchers.IO)
