package com.clma.app.data.botmode.routine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RoutineOptions(
    val interval: RoutineInterval? = null,
    val overlap: RoutineOverlap = RoutineOverlap.SKIP,
    val condition: String = "",
    val changedUrl: String = "",
    val sameConversation: Boolean = false,
)

@Serializable
data class RoutineState(
    val options: RoutineOptions = RoutineOptions(),
    val conversationId: String? = null,
    val pageHash: String? = null,
    val checkPacing: RoutineCheckPacing = RoutineCheckPacing(),
    val queuedSlot: Long? = null,
    val queuedManual: Boolean = false,
    val quota: QuotaRetry? = null,
)

@Serializable
data class QuotaRetry(
    val runId: String,
    val atMs: Long,
    val pageHash: String? = null,
    val changedUrl: String = "",
    val manual: Boolean = false,
)

@Serializable
data class CheckBack(
    val id: String,
    val conversationId: String,
    val assistantId: String,
    val atMs: Long,
    val note: String,
    val condition: String = "",
)

@Serializable
data class RoutineSnapshot(
    val routines: Map<String, RoutineState> = emptyMap(),
    val checkBacks: Map<String, CheckBack> = emptyMap(),
    val backupModels: Map<String, String> = emptyMap(),
)

object RoutineCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(snapshot: RoutineSnapshot): String = json.encodeToString(snapshot)
    fun decode(value: String): RoutineSnapshot = json.decodeFromString(value)
}
