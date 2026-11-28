// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/data/tools.ts, server/data/types.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.data

import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.isBot
import kotlinx.serialization.json.*

internal data class BotDataCard(
    val title: String?,
    val table: BotDataTable,
    val chart: Boolean,
) {
    companion object {
        const val TOOL_NAME = "data_show"
        private const val SUCCESS = """{"status":"data_card_ready"}"""

        fun toolFor(assistant: Assistant): Tool? = if (!assistant.isBot) null else Tool(
            name = TOOL_NAME,
            description = "Show structured data as a local table or chart in this conversation. " +
                "Supply up to 24 unique column names and 2,000 rows of scalar JSON values. " +
                "For a chart put labels first and numeric values next. The user can filter rows and query table data with SQLite.",
            parameters = {
                InputSchema.Obj(
                    buildJsonObject {
                        put("title", buildJsonObject { put("type", "string") })
                        put("kind", Json.parseToJsonElement("""{"type":"string","enum":["table","chart"]}"""))
                        put("columns", Json.parseToJsonElement("""{"type":"array","items":{"type":"string"}}"""))
                        put("rows", Json.parseToJsonElement("""{"type":"array","items":{"type":"array","items":{"type":["string","number","boolean","null"]}}}"""))
                    },
                    required = listOf("columns", "rows"),
                )
            },
            execute = { input ->
                val result = if (parse(input) != null) SUCCESS else
                    """{"error":"Provide scalar rows matching unique column names within the data card size limits."}"""
                listOf(UIMessagePart.Text(result))
            },
        )

        fun fromTool(tool: UIMessagePart.Tool): BotDataCard? {
            if (tool.toolName != TOOL_NAME || tool.output.filterIsInstance<UIMessagePart.Text>()
                    .none { it.text == SUCCESS }
            ) return null
            if (tool.input.length > 384_000) return null
            return parse(tool.inputAsJson())
        }

        private fun parse(input: JsonElement): BotDataCard? {
            val args = input as? JsonObject ?: return null
            val rawColumns = args["columns"] as? JsonArray ?: return null
            if (rawColumns.size !in 1..24) return null
            val columns = rawColumns.map { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotBlank() && it.length <= 80 && '\u0000' !in it } ?: return null
            }
            if (columns.distinctBy { it.lowercase() }.size != columns.size) return null
            val rawRows = args["rows"] as? JsonArray ?: return null
            if (rawRows.size > 2_000) return null
            val title = (args["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(160)
            val kind = (args["kind"] as? JsonPrimitive)?.content ?: "table"
            if (kind !in setOf("table", "chart")) return null
            var length = columns.sumOf { JsonPrimitive(it).toString().length } + (title?.length ?: 0)
            val rows = rawRows.map { element ->
                val row = element as? JsonArray ?: return null
                if (row.size != columns.size) return null
                row.map { value ->
                    val cell = value as? JsonPrimitive ?: return null
                    if (cell.content.length > 2_048) return null
                    length += cell.toString().length + 2
                    if (length > 350_000) return null
                    when {
                        cell == JsonNull -> null
                        cell.isString -> cell.content
                        else -> cell.booleanOrNull?.let { if (it) 1L else 0L }
                            ?: cell.longOrNull
                            ?: cell.doubleOrNull?.takeIf { it.isFinite() } ?: return null
                    }
                }
            }
            return BotDataCard(title, BotDataTable(columns, rows), kind == "chart")
        }
    }
}

internal data class BotDataTable(val columns: List<String>, val rows: List<List<Any?>>, val truncated: Boolean = false)
