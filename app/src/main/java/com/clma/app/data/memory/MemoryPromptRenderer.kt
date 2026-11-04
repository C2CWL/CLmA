package com.clma.app.data.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Scope is selected by the caller; hot records and the cold index stay separate categories. */
internal object MemoryPromptRenderer {
    private val json = Json { prettyPrint = true }

    fun hot(records: List<Pair<Int, String>>): String = buildString {
        append("\n**Memories**\n")
        append("These are memories stored via the memory_tool that you can reference in future conversations.\n")
        val payload = buildJsonArray {
            records.sortedBy { it.first }.forEach { (id, content) ->
                add(buildJsonObject {
                    put("id", id)
                    put("content", content)
                })
            }
        }
        append(json.encodeToString(payload))
        append('\n')
    }

    fun coldIndex(content: String, maxChars: Int): String? {
        val index = content.trim()
        if (index.isEmpty()) return null
        return buildString {
            append("**Cold Memory Index (INDEX.md)**\n")
            append("Below is the current index of the cold-memory knowledge base. Use `memory_read` to retrieve documents when needed. You do not need to call `memory_index` unless you suspect the index on disk has changed.\n")
            append("```markdown\n")
            append(index.take(maxChars))
            if (index.length > maxChars) {
                append("\n[Index truncated to $maxChars characters. Use memory_index to read the full listing.]")
            }
            append("\n```")
        }
    }
}
