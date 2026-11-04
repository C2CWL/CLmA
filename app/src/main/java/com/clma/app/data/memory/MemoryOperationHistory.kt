package com.clma.app.data.memory

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local metadata only: never retain memory prose, prompts or model reasoning. Cold writes keep the
 * Markdown file name so an individual record can be identified; hot records keep the row id. */
@Serializable
data class MemoryOperation(
    val timestamp: Long,
    val scope: String,
    val category: String,
    val action: String,
    val source: String,
    val recordId: Int? = null,
    val beforeChars: Int = 0,
    val afterChars: Int = 0,
    /** Cold memory Markdown file name; null for hot records and for journals written before it existed. */
    val name: String? = null,
)

class MemoryOperationHistory(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _persistenceFailed = MutableStateFlow(false)
    val persistenceFailed = _persistenceFailed.asStateFlow()
    private val _entries = MutableStateFlow(load())
    val entries = _entries.asStateFlow()

    private fun load(): List<MemoryOperation> = try {
        if (file.isFile) json.decodeFromString<List<MemoryOperation>>(file.readText()).takeLast(LIMIT) else emptyList()
    } catch (_: Exception) {
        _persistenceFailed.value = true
        emptyList()
    }

    /** Call after a successful mutation, on the IO dispatcher. A journal failure must not retry the mutation. */
    @Synchronized
    fun record(operation: MemoryOperation) {
        val next = (_entries.value + operation).takeLast(LIMIT)
        _entries.value = next
        try {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val pending = File(file.parentFile, "${file.name}.pending")
            pending.writeText(json.encodeToString(next))
            check(pending.renameTo(file))
            _persistenceFailed.value = false
        } catch (_: Exception) {
            _persistenceFailed.value = true
        }
    }

    companion object {
        const val LIMIT = 200
    }
}
