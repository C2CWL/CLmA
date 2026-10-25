package com.clma.app.data.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

/**
 * Result of reading a sliced or filtered tool output file.
 */
@Serializable
data class ToolOutputReadResult(
    val file: String,
    val total_lines: Int,
    val total_chars: Long,
    val offset: Int,
    val limit: Int,
    val returned_lines: Int,
    val has_more: Boolean,
    val next_offset: Int? = null,
    val pattern: String? = null,
    val matched_total_lines: Int? = null,
    val truncated_by_max_chars: Boolean = false,
    val content: String,
)

/**
 * Safely reads, slices and filters tool spillover logs stored under `filesDir/tool_outputs`.
 *
 * Enforces strict boundary checks against directory traversal and provides line-based
 * pagination, keyword/regex matching, and single-call character budgets.
 */
object ToolOutputReader {

    const val DEFAULT_LIMIT = 200
    const val MAX_LIMIT = 1000
    const val DEFAULT_MAX_CHARS = 16_000
    const val HARD_MAX_CHARS = 64_000

    /**
     * Resolves and verifies that [fileName] exists strictly inside [outputDir] without path traversal.
     */
    fun resolveSafeFile(outputDir: File, fileName: String): File {
        val trimmed = fileName.trim()
        require(trimmed.isNotBlank()) { "File name must not be blank" }
        require(!trimmed.contains('/') && !trimmed.contains('\\') && !trimmed.contains("..")) {
            "Path traversal detected or invalid characters in file name: $trimmed"
        }

        val canonicalDir = outputDir.canonicalFile
        var candidate = File(outputDir, trimmed)
        if (!candidate.exists() && !trimmed.endsWith(ToolOutputNames.EXTENSION)) {
            candidate = File(outputDir, trimmed + ToolOutputNames.EXTENSION)
        }

        val canonicalTarget = candidate.canonicalFile
        if (canonicalTarget.parentFile != canonicalDir) {
            throw SecurityException("Path traversal rejected: $trimmed")
        }
        if (!canonicalTarget.exists() || !canonicalTarget.isFile) {
            throw FileNotFoundException("Tool output file not found: $trimmed")
        }
        return canonicalTarget
    }

    /**
     * Reads a sliced section of [fileName] in [outputDir].
     *
     * @param offset 1-based line offset
     * @param limit maximum number of lines to return (1..[MAX_LIMIT])
     * @param pattern optional regex or keyword substring to filter lines
     * @param maxChars maximum characters of output content (100..[HARD_MAX_CHARS])
     */
    fun read(
        outputDir: File,
        fileName: String,
        offset: Int = 1,
        limit: Int = DEFAULT_LIMIT,
        pattern: String? = null,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): ToolOutputReadResult {
        val file = resolveSafeFile(outputDir, fileName)
        val safeOffset = offset.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, MAX_LIMIT)
        val safeMaxChars = maxChars.coerceIn(100, HARD_MAX_CHARS)

        val regex: Regex? = if (!pattern.isNullOrBlank()) {
            runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
        } else {
            null
        }

        var totalLines = 0
        var totalChars = 0L
        var matchedTotalLines = 0

        val linesToReturn = mutableListOf<String>()
        var accumulatedChars = 0
        var truncatedByChars = false

        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                totalLines++
                totalChars += line.length + 1 // +1 for newline approximation

                val matches = when {
                    pattern.isNullOrBlank() -> true
                    regex != null -> regex.containsMatchIn(line)
                    else -> line.contains(pattern, ignoreCase = true)
                }

                if (matches) {
                    matchedTotalLines++
                    val matchIndex = matchedTotalLines // 1-based index in matching sequence
                    if (matchIndex >= safeOffset && linesToReturn.size < safeLimit && !truncatedByChars) {
                        val formatted = "$totalLines: $line"
                        if (accumulatedChars + formatted.length + 1 > safeMaxChars && linesToReturn.isNotEmpty()) {
                            truncatedByChars = true
                        } else {
                            linesToReturn.add(formatted)
                            accumulatedChars += formatted.length + 1
                        }
                    }
                }
            }
        }

        val totalApplicable = if (pattern.isNullOrBlank()) totalLines else matchedTotalLines
        val returnedCount = linesToReturn.size
        val hasMore = (safeOffset + returnedCount <= totalApplicable) && returnedCount > 0

        val nextOffset = if (hasMore) {
            safeOffset + returnedCount
        } else {
            null
        }

        return ToolOutputReadResult(
            file = file.name,
            total_lines = totalLines,
            total_chars = totalChars,
            offset = safeOffset,
            limit = safeLimit,
            returned_lines = returnedCount,
            has_more = hasMore,
            next_offset = nextOffset,
            pattern = pattern?.ifBlank { null },
            matched_total_lines = if (pattern.isNullOrBlank()) null else matchedTotalLines,
            truncated_by_max_chars = truncatedByChars,
            content = linesToReturn.joinToString("\n"),
        )
    }
}
