package com.clma.app.data.ai.tools.local

import android.os.Build
import android.os.SystemClock
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Read-only, bounded foreground-screen wait adapted from moxw (AGPL-3.0, 81bbb7e). */
internal fun waitForTool(): Tool = Tool(
    name = "wait_for",
    description = "Wait for all supplied foreground conditions: visible text (case-insensitive substring), exact package_name, or screen_change relative to the first readable screen. Returns matched=false on timeout. Does not touch or switch apps.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("text", buildJsonObject { put("type", "string") })
            put("package_name", buildJsonObject { put("type", "string") })
            put("screen_change", buildJsonObject { put("type", "boolean") })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "100–15000 ms; default 5000 ms")
            })
        }, required = emptyList())
    },
    execute = { input ->
        val args = input as? JsonObject ?: JsonObject(emptyMap())
        fun stringArg(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }
            ?.content?.trim()?.takeIf { it.isNotEmpty() }
        val text = stringArg("text")
        val pkg = stringArg("package_name")
        val change = (args["screen_change"] as? JsonPrimitive)?.booleanOrNull == true
        val timeout = ((args["timeout_ms"] as? JsonPrimitive)?.longOrNull ?: 5000L).coerceIn(100L, 15000L)
        val result = if (text == null && pkg == null && !change) {
            buildJsonObject { put("error", "At least one wait condition is required") }
        } else AccessibilityServiceHandle.withService { svc ->
            val start = SystemClock.elapsedRealtime()
            var baseline: Long? = null
            var matched = false
            while (true) {
                if (!AccessibilityServiceHandle.isRunning()) return@withService AccessibilityServiceHandle.notActiveEnvelope()
                val root = svc.rootInActiveWindow
                if (root != null) {
                    try {
                        val hash = if (change) treeHash(svc, root) else null
                        val changed = !change || (baseline != null && hash != null && hash != baseline)
                        if (baseline == null) baseline = hash
                        if (changed && (pkg == null || root.packageName?.toString() == pkg)) {
                            matched = text == null || svc.traverseTree(
                                root = root,
                                filter = { node, _ ->
                                    node.isVisibleToUser && !node.isPassword &&
                                        (node.text?.contains(text, ignoreCase = true) == true ||
                                            node.contentDescription?.contains(text, ignoreCase = true) == true)
                                },
                                cap = 1,
                                emit = { _, _, _ -> },
                                recycle = true,
                            ).first > 0
                        }
                    } finally {
                        @Suppress("DEPRECATION")
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) root.recycle()
                    }
                }
                val left = timeout - (SystemClock.elapsedRealtime() - start)
                if (matched || left <= 0) break
                delay(minOf(300L, left))
            }
            buildJsonObject {
                put("matched", matched)
                put("elapsed_ms", SystemClock.elapsedRealtime() - start)
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
