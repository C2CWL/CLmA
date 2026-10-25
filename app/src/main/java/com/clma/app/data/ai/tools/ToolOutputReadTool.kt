package com.clma.app.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.files.FileFolders
import java.io.File

/**
 * Builds the [tool_output_read] tool allowing models to safely inspect spilled tool outputs.
 */
fun createToolOutputReadTool(context: Context): Tool {
    val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS)
    return Tool(
        name = "tool_output_read",
        description = """
            Read, slice, or filter a long tool output that was spilled to disk under `tool_outputs/`.
            When earlier tool results say "Output was truncated and saved to: /tool_outputs/...", use this tool
            to inspect specific line ranges, search for patterns/errors, or read subsequent chunks without blowing up the context window.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("file", buildJsonObject {
                        put("type", "string")
                        put("description", "File name of the spilled output (e.g. 'call_abc123.txt' or 'call_abc123').")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "1-based line number to start reading from. Defaults to 1.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of lines to return (1..1000). Defaults to 200.")
                    })
                    put("pattern", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional regex or keyword substring to filter lines. When provided, only matching lines are returned.")
                    })
                    put("max_chars", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum characters to return (100..64000). Defaults to 16000.")
                    })
                },
                required = listOf("file"),
            )
        },
        execute = { args ->
            val json = args.jsonObject
            val fileName = json["file"]?.jsonPrimitive?.contentOrNull?.trim()
            if (fileName.isNullOrBlank()) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        Json.encodeToString(
                            JsonObject.serializer(),
                            buildJsonObject {
                                put("error", "file parameter is required and must not be blank")
                            }
                        )
                    )
                )
            }
            val offset = json["offset"]?.jsonPrimitive?.intOrNull ?: 1
            val limit = json["limit"]?.jsonPrimitive?.intOrNull ?: ToolOutputReader.DEFAULT_LIMIT
            val pattern = json["pattern"]?.jsonPrimitive?.contentOrNull
            val maxChars = json["max_chars"]?.jsonPrimitive?.intOrNull ?: ToolOutputReader.DEFAULT_MAX_CHARS

            try {
                val result = ToolOutputReader.read(
                    outputDir = outputDir,
                    fileName = fileName,
                    offset = offset,
                    limit = limit,
                    pattern = pattern,
                    maxChars = maxChars,
                )
                listOf(UIMessagePart.Text(Json.encodeToString(ToolOutputReadResult.serializer(), result)))
            } catch (e: Exception) {
                listOf(
                    UIMessagePart.Text(
                        Json.encodeToString(
                            JsonObject.serializer(),
                            buildJsonObject {
                                put("error", e.message ?: "Failed to read tool output")
                            }
                        )
                    )
                )
            }
        }
    )
}
