// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/drivers/agents-catalog.ts, server/drivers/agents-tool-groups.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.memory

import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.common.android.DiagnosticRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object BotMemoryTools {
    val NAMES = setOf("memory_update", "memory_read_topic", "memory_list_topics", "memory_log")

    fun build(store: BotMemoryStore, conversationId: String, eligible: () -> Boolean): List<Tool> = listOf(
        tool("memory_update",
            "Update your long-term notes. append saves one verified fact per call, stamped with today's date. " +
                "Use a topic for longer-lived details. forget removes matching lines from MEMORY.md and topic notes.",
            listOf("action", "text", "old_text", "topic"), listOf("action"), eligible,
        ) { args ->
            when (args.required("action")) {
                "append" -> { store.remember(args.required("text"), args["topic"]?.jsonPrimitive?.content); "Saved." }
                "forget" -> "Removed ${store.forget(args.required("old_text"))} matching lines from notes."
                else -> throw IllegalArgumentException("Use append or forget")
            }
        },
        tool("memory_read_topic", "Read one of your own topic notes by name.", listOf("topic"), listOf("topic"), eligible) {
            store.readTopic(it.required("topic")).ifBlank { "No notes in this topic." }
        },
        tool("memory_list_topics", "List your own memory topic names.", emptyList(), emptyList(), eligible) {
            store.topics().joinToString("\n").ifBlank { "No topics yet." }
        },
        tool("memory_log",
            "Write one line to today's log, stamped with the time and conversation. Use it for events worth a trace. " +
                "A fact that should hold in future sessions goes to memory_update instead.",
            listOf("text"), listOf("text"), eligible,
        ) {
            store.appendLog(DiagnosticRedactor.text(it.required("text")), conversationId)
            "Logged."
        },
    )

    private fun tool(
        name: String,
        description: String,
        fields: List<String>,
        required: List<String>,
        eligible: () -> Boolean,
        execute: (JsonObject) -> String,
    ) = Tool(
        name = name,
        description = description,
        parameters = {
            InputSchema.Obj(buildJsonObject {
                fields.forEach { field ->
                    put(field, buildJsonObject {
                        put("type", "string")
                        if (field == "action") put("enum", buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("append"))
                            add(kotlinx.serialization.json.JsonPrimitive("forget"))
                        })
                    })
                }
            }, required = required)
        },
        execute = { args ->
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(eligible()) { "Bot memory is unavailable for this turn" }
                    execute(args.jsonObject)
                }.getOrElse { "Memory operation failed: ${it.message}" }
            }
            listOf(UIMessagePart.Text(result))
        },
    )

    private fun JsonObject.required(name: String): String =
        requireNotNull(this[name]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)) { "$name is required" }
}
