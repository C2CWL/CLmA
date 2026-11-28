// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/chat-widgets.ts, server/drivers/agents-widget-tools.ts, shared/widget.ts
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode

import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.UUID
import kotlin.uuid.Uuid

internal const val BOT_WIDGET_TOOL = "show_widget"
private const val WIDGET_SHOWN = """{"shown":true}"""
private const val MAX_WIDGET_LENGTH = 16_000

@Serializable
internal data class BotChatWidget(
    val title: String,
    val caption: String? = null,
    val buttons: List<Button> = emptyList(),
    val datePicker: DatePicker? = null,
    val table: Table? = null,
    val chart: Chart? = null,
) {
    @Serializable
    data class Button(val id: String, val label: String)

    @Serializable
    data class DatePicker(val label: String, val defaultDate: String? = null)

    @Serializable
    data class Table(val columns: List<String>, val rows: List<List<String>>)

    @Serializable
    data class Chart(val label: String, val points: List<Point>)

    @Serializable
    data class Point(val label: String, val value: Double)

    fun isValid(): Boolean =
        title.isNotBlank() && title.length <= 120 && (caption?.length ?: 0) <= 500 &&
            (buttons.isNotEmpty() || datePicker != null || table != null || chart != null) &&
            buttons.size <= 6 && buttons.map { it.id }.distinct().size == buttons.size &&
            buttons.all { it.id.matches(actionId) && it.label.validLabel() } &&
            (datePicker == null || (datePicker.label.validLabel() &&
                (datePicker.defaultDate == null || widgetDate(datePicker.defaultDate) != null))) &&
            (table == null || (table.columns.size in 1..8 && table.columns.all { it.validLabel() } &&
                table.rows.size in 1..30 && table.rows.all { row ->
                    row.size == table.columns.size && row.all { it.length <= 160 }
                })) &&
            (chart == null || (chart.label.validLabel() && chart.points.size in 1..24 &&
                chart.points.all { it.label.validLabel() && it.value.isFinite() }))

    fun selection(buttonId: String?, date: String?): String? = when {
        buttonId != null && date == null -> buttons.find { it.id == buttonId }?.let { button ->
            buildJsonObject {
                put("button_id", button.id)
                put("label", button.label)
            }.toString()
        }
        buttonId == null && date != null && datePicker != null && widgetDate(date) != null ->
            buildJsonObject { put("date", date) }.toString()
        else -> null
    }
}

private val actionId = Regex("[A-Za-z0-9_-]{1,40}")
private val isoDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private fun String.validLabel() = isNotBlank() && length <= 80

internal fun widgetDate(value: String): LocalDate? =
    if (isoDate.matches(value)) {
        runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { it.year in 1900..2100 }
    } else {
        null
    }

internal fun decodeBotChatWidget(input: JsonElement): BotChatWidget? {
    val widget = (input as? JsonObject)?.get("widget") as? JsonObject ?: return null
    if (widget.toString().length > MAX_WIDGET_LENGTH) return null
    return runCatching { Json.decodeFromJsonElement(BotChatWidget.serializer(), widget) }
        .getOrNull()?.takeIf { it.isValid() }
}

internal fun UIMessagePart.Tool.botChatWidget(): BotChatWidget? {
    if (toolName != BOT_WIDGET_TOOL || input.length > MAX_WIDGET_LENGTH + 128 ||
        (output.firstOrNull() as? UIMessagePart.Text)?.text != WIDGET_SHOWN
    ) return null
    return decodeBotChatWidget(inputAsJsonObject())
}

internal fun widgetReplyId(conversationId: Uuid, messageId: Uuid, toolCallId: String): Uuid {
    val key = "widget-reply:$conversationId:$messageId:$toolCallId"
    return Uuid.parse(UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString())
}

internal fun createBotWidgetTool() = Tool(
    name = BOT_WIDGET_TOOL,
    description = """
        Display a card in the current Bot chat. widget is a JSON object with a required title and
        optional caption, buttons [{id,label}] (up to 6, unique IDs of letters/digits/_/-),
        datePicker {label,defaultDate?} (YYYY-MM-DD, years 1900–2100), table {columns:[string],
        rows:[[string]]} (up to 8 columns and 30 rows), and chart {label,points:[{label,value}]}
        (numeric bar chart, up to 24 points). Include at least one control, table, or chart.
        A button or date choice arrives as the user's next message. Do not wait for the choice
        or call the tool again unless a new card is needed. Never request secrets in a card.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("widget", buildJsonObject { put("type", "object") })
            },
            required = listOf("widget"),
        )
    },
    execute = { input ->
        val result = if (decodeBotChatWidget(input) != null && input.toString().length <= MAX_WIDGET_LENGTH + 128) {
            WIDGET_SHOWN
        } else {
            """{"error":"Invalid widget. Check the tool description for fields and limits."}"""
        }
        listOf(UIMessagePart.Text(result))
    },
)
