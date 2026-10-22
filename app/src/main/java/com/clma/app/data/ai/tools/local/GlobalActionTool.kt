package com.clma.app.data.ai.tools.local

import android.accessibilityservice.AccessibilityService
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.AgentTurnTracker
import com.clma.app.data.ai.tools.ToolInvocationContext
import com.clma.app.service.ActionLogEntry

private val ACTION_MAP: Map<String, Int> = mapOf(
    "back" to AccessibilityService.GLOBAL_ACTION_BACK,
    "home" to AccessibilityService.GLOBAL_ACTION_HOME,
    "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
    "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
    "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
    "lock_screen" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
    "power_dialog" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
)

fun globalActionTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "global_action",
    description = """
        Perform an Android system action: back / home / recents / notifications / quick_settings /
        lock_screen / power_dialog. success only means the OS accepted the action; verify the
        outcome via the returned "after" object (e.g. after home, confirm shade_open is absent
        and the launcher package is foreground before assuming you are on the home screen).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { ACTION_MAP.keys.forEach { add(it) } })
                    put("description", "Which system action to perform")
                })
            },
            required = listOf("action")
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        val wakeOk = com.clma.app.service.RikkaAccessibilityService.instance
            ?.let { wakeScreenIfNeeded(it) } ?: true
        val action = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull
        val code = action?.let { ACTION_MAP[it] }
        if (action == null || code == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "action must be one of ${ACTION_MAP.keys.toList()}")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            withActionEnvelope(svc) { _ ->
                val ok = svc.performGlobalAction(code)
                svc.appendLog(
                    ActionLogEntry(
                        type = "global_action",
                        paramsSummary = action,
                        success = ok,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                buildJsonObject {
                    put("success", ok)
                    if (!ok) put("reason", "rejected_by_os")
                    if (!wakeOk) put("wake_failed", true)
                }
            }
        }
        streamer.streamIfHeadless(invocationContext, "GlobalAction $action")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
