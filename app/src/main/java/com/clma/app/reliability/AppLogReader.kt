package com.clma.app.reliability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Character offsets preserve even a single log line longer than the response budget. */
internal object AppLogReader {
    fun read(text: String, keyword: String, start: Int, limit: Int, maxChars: Int): JsonObject {
        val filtered = if (keyword.isEmpty()) text else text.lineSequence()
            .filter { it.contains(keyword, ignoreCase = true) }.joinToString("\n")
        val offset = start.coerceAtMost(filtered.length)
        val page = filtered.substring(offset, offset + minOf(maxChars, filtered.length - offset))
        var end = page.length
        var lines = 0
        for (index in page.indices) {
            if (page[index] == '\n' && ++lines == limit) {
                end = index + 1
                break
            }
        }
        val next = offset + end
        return buildJsonObject {
            put("text", page.take(end))
            put("start_index", offset)
            put("total_chars", filtered.length)
            put("truncated", next < filtered.length)
            if (next < filtered.length) put("next_start_index", next)
        }
    }
}
