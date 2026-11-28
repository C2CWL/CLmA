// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/drivers/agents-check-back-tool.ts, server/routes/check-backs.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.routine

import android.content.Context
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.R
import com.clma.app.data.datastore.SettingsStore
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object CheckBackTool : KoinComponent {
    private val store: RoutineStore by inject()

    fun build(
        context: Context,
        conversationId: Uuid,
        assistantId: Uuid,
        settings: SettingsStore,
        eligible: () -> Boolean,
    ) = Tool(
        name = "check_back",
        description = "Schedule a return to this conversation with when, note and optional if. " +
            "Use 'in 2 hours', 'tomorrow 9am', 'Fri 9am' or ISO. No arguments lists pending entries. Cancel by ID or 'all'.",
        parameters = {
            InputSchema.Obj(buildJsonObject {
                for (key in listOf("when", "note", "if", "cancel")) {
                    put(key, buildJsonObject { put("type", "string") })
                }
            }, required = emptyList())
        },
        execute = { raw ->
            check(eligible())
            val args = raw.jsonObject
            val conversation = conversationId.toString()
            val zone = routineZone(settings.settingsFlow.value.customTimezone)
            val pending = store.read().checkBacks.values.filter { it.conversationId == conversation }
            fun arg(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()
            fun line(item: CheckBack): String {
                val time = Instant.ofEpochMilli(item.atMs).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                return context.getString(R.string.routine2_check_back_pending, time, item.note) + " (" + item.id + ")"
            }

            val cancel = arg("cancel")
            val whenText = arg("when")
            val note = arg("note")
            val condition = arg("if")
            val result = when {
                args.isEmpty() -> pending.sortedBy { it.atMs }.joinToString("\n", transform = ::line)
                    .ifEmpty { context.getString(R.string.routine2_check_back_empty) }
                cancel != null && args.size == 1 -> {
                    val selected = pending.filter { cancel == "all" || it.id == cancel }
                    val cancelled = selected.count { CheckBackDelivery.cancel(context, store, it.id, conversation) }
                    context.getString(R.string.routine2_check_back_cancelled, cancelled)
                }
                whenText != null && note != null && cancel == null && note.length in 1..2000 && (condition?.length ?: 0) <= 1000 -> {
                    val at = parseCheckBackTime(whenText, ZonedDateTime.now(zone))
                    if (at == null) context.getString(R.string.routine2_check_back_invalid)
                    else {
                        val item = CheckBack(Uuid.random().toString(), conversation, assistantId.toString(),
                            at.toEpochMilli(), note, condition.orEmpty())
                        var saved = false
                        store.update { snapshot ->
                            if (snapshot.checkBacks.values.count { it.conversationId == conversation } >= 20) return@update snapshot
                            saved = true
                            snapshot.copy(checkBacks = snapshot.checkBacks + (item.id to item))
                        }
                        if (!saved) context.getString(R.string.routine2_check_back_limit)
                        else {
                            CheckBackDelivery.schedule(context, item)
                            line(item)
                        }
                    }
                }
                else -> context.getString(R.string.routine2_check_back_invalid)
            }
            listOf(UIMessagePart.Text(result))
        },
    )
}
