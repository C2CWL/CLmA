package com.clma.app.data.ai.tools.local

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.clma.app.virtualdisplay.VirtualDisplayLauncher
import com.clma.app.virtualdisplay.VirtualDisplayUnicodeInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.shizuku.ShizukuManager

// Pure helpers (validateCreateParams, build*Command) live in VirtualDisplayRules.kt
// so they can be unit-tested without Android dependencies.

private const val SHELL_TIMEOUT_MS = 10_000

/** Wraps a Shizuku exec result into a tool result (list of UIMessagePart.Text). */
private fun shizukuResultToParts(result: JsonObject): List<UIMessagePart.Text> =
    listOf(UIMessagePart.Text(result.toString()))

/** Captured stdout of a Shizuku exec result, or "" when the call failed before running. */
private fun JsonObject.stdoutOf(): String =
    this["stdout"]?.jsonPrimitive?.contentOrNull ?: ""

// --- tool definitions --------------------------------------------------------------------

/**
 * Create a virtual display that runs apps off-screen. The agent can launch apps on it,
 * inject touch/input, and screenshot it via `take_screenshot(display_id=N)` or inspect its
 * window tree via `read_window_tree(display_id=N)` — all without stealing the foreground
 * from the user.
 *
 * Requires no special permission for creation (uses DisplayManager public API). Input
 * injection and app launching need Shizuku (shell UID). Screenshots use the ImageReader
 * backing the display's Surface. Window tree inspection uses the accessibility service's
 * `getWindows()` filtered by display ID (API 33+).
 */
fun virtualDisplayCreateTool(context: Context): Tool = Tool(
    name = "virtual_display_create",
    description = """
        Create a virtual (off-screen) display so apps can run without taking over the user's
        foreground. Returns the display_id — use it with virtual_display_launch, _tap, _swipe,
        _key, _text, and take_screenshot(display_id=N). Resolution is 16-aligned internally.
        Width/height in pixels, density in DPI (default 320). Max 4096 per side.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("width", buildJsonObject {
                    put("type", "integer")
                    put("description", "Display width in pixels (1-4096)")
                })
                put("height", buildJsonObject {
                    put("type", "integer")
                    put("description", "Display height in pixels (1-4096)")
                })
                put("density", buildJsonObject {
                    put("type", "integer")
                    put("description", "Density DPI (default 320, range 1-640)")
                })
            },
            required = listOf("width", "height")
        )
    },
    execute = { input ->
        val width = input.jsonObject["width"]?.jsonPrimitive?.intOrNull
        val height = input.jsonObject["height"]?.jsonPrimitive?.intOrNull
        val density = input.jsonObject["density"]?.jsonPrimitive?.intOrNull
        if (width == null || height == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "width and height are required") }.toString()
            ))
        }
        when (val result = VirtualDisplayController.create(context, width, height, density ?: 320)) {
            is VirtualDisplayController.Result.Ok -> listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("display_id", result.value)
                    put("width", (width + 15) and 15.inv())
                    put("height", (height + 15) and 15.inv())
                    put("density", density ?: 320)
                    put(
                        "hint",
                        "Use take_screenshot(display_id=${result.value}) or read_window_tree(display_id=${result.value}) " +
                            "to capture / inspect this display. Closing the floating preview (✕) does NOT destroy it — " +
                            "call virtual_display_show_preview to reopen, or virtual_display_destroy to tear it down.",
                    )
                }.toString()
            ))
            is VirtualDisplayController.Result.Error -> listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("error", result.code)
                    put("detail", result.message)
                }.toString()
            ))
        }
    }
)

fun virtualDisplayDestroyTool(): Tool = Tool(
    name = "virtual_display_destroy",
    description = """
        Destroy a virtual display by its display_id and release its surface / resources.
        This is NOT the same as hiding the floating preview: ✕ / virtual_display_hide_preview
        only closes the preview window while the display keeps running. Prefer destroy when the
        user is done with the virtual screen.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Display id returned by virtual_display_create")
                })
            },
            required = listOf("display_id")
        )
    },
    execute = { input ->
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull
        if (displayId == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_id is required") }.toString()
            ))
        }
        val destroyed = VirtualDisplayController.destroy(displayId)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", destroyed)
                if (!destroyed) put("reason", "display not found (already destroyed or never created)")
            }.toString()
        ))
    }
)

fun virtualDisplayListTool(): Tool = Tool(
    name = "virtual_display_list",
    description = "List all virtual displays created by this app session. Returns display_id, name, dimensions, and density for each.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { }
        )
    },
    execute = { _ ->
        val list = VirtualDisplayController.list()
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("count", list.size)
                putJsonArray("displays") {
                    list.forEach { info ->
                        add(buildJsonObject {
                            put("display_id", info.displayId)
                            put("name", info.name)
                            put("width", info.width)
                            put("height", info.height)
                            put("density", info.densityDpi)
                        })
                    }
                }
            }.toString()
        ))
    }
)

fun virtualDisplayShowPreviewTool(context: Context): Tool = Tool(
    name = "virtual_display_show_preview",
    description = """
        Show (or re-show) the floating live preview for an existing virtual display. Closing the
        preview with ✕ does not destroy the display — call this to bring the preview back without
        creating a new display. Requires SYSTEM_ALERT_WINDOW. Do not use main-screen UI tools to
        "open virtual display".
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Display id returned by virtual_display_create / virtual_display_list")
                })
            },
            required = listOf("display_id")
        )
    },
    execute = { input ->
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull
        if (displayId == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_id is required") }.toString()
            ))
        }
        if (!VirtualDisplayController.contains(displayId)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("error", "display_not_found")
                    put("detail", "No managed virtual display with id=$displayId. Create one first or call virtual_display_list.")
                }.toString()
            ))
        }
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("error", "overlay_permission_required")
                    put("detail", "SYSTEM_ALERT_WINDOW is not granted; cannot show the floating preview.")
                }.toString()
            ))
        }
        VirtualDisplayController.showPreview(app, displayId)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", true)
                put("display_id", displayId)
                put("hint", "Preview shown. ✕ only hides it; 🗑 / virtual_display_destroy tears the display down.")
            }.toString()
        ))
    }
)

fun virtualDisplayHidePreviewTool(): Tool = Tool(
    name = "virtual_display_hide_preview",
    description = """
        Hide the floating live preview for a virtual display without destroying it. The display
        keeps running in the background — use virtual_display_show_preview to reopen, or
        virtual_display_destroy to release resources.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Display id whose floating preview should be hidden")
                })
            },
            required = listOf("display_id")
        )
    },
    execute = { input ->
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull
        if (displayId == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_id is required") }.toString()
            ))
        }
        if (!VirtualDisplayController.contains(displayId)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("error", "display_not_found")
                    put("detail", "No managed virtual display with id=$displayId")
                }.toString()
            ))
        }
        VirtualDisplayController.hidePreview(displayId)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", true)
                put("display_id", displayId)
                put("hint", "Preview hidden; display still running. Call virtual_display_show_preview or virtual_display_destroy.")
            }.toString()
        ))
    }
)

fun virtualDisplayLaunchTool(context: Context): Tool = Tool(
    name = "virtual_display_launch",
    description = """
        Launch an app on a virtual display. Requires Shizuku (shell UID) to run `am start
        --display`. Use the package_name from list_installed_apps; activity is optional — when
        omitted the app's launcher activity is resolved. The task is always started as a new
        task, so the display actually renders: if the app is already running on another
        display it is force-stopped first (unsaved state is lost) and the result carries
        force_stopped=true.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Virtual display id")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "App package name (e.g. com.android.chrome)")
                })
                put("activity", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional activity class name to launch a specific screen (e.g. .MainActivity). Omit to use the launcher activity.")
                })
            },
            required = listOf("display_id", "package_name")
        )
    },
    execute = { input ->
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull
        val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
        val activity = input.jsonObject["activity"]?.jsonPrimitive?.contentOrNull
        if (displayId == null || pkg.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_id and package_name are required") }.toString()
            ))
        }
        if (!isValidPackageName(pkg)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("error", "invalid_package_name")
                    put("detail", "Expected a package name like com.example.app, got: $pkg")
                }.toString()
            ))
        }
        if (!isValidActivityName(activity)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("error", "invalid_activity")
                    put("detail", "Expected an activity class name like .MainActivity or com.example.app.MainActivity, got: $activity")
                }.toString()
            ))
        }
        if (!VirtualDisplayController.contains(displayId)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("error", "display_not_found")
                    put("detail", "Create the display first with virtual_display_create")
                }.toString()
            ))
        }
        val cmd = buildLaunchCommand(displayId, pkg, activity)
        var result = ShizukuManager.exec(context, cmd, SHELL_TIMEOUT_MS)
        var forceStopped = false
        // The app already had a task on another display: `am` delivered the intent to that
        // instance and the task lands on the virtual display paused / not visible, so it never
        // renders and the a11y tree is empty. force-stop + retry is the only reliable fix.
        if (wasDeliveredToRunningInstance(result.stdoutOf())) {
            ShizukuManager.exec(context, buildForceStopCommand(pkg), SHELL_TIMEOUT_MS)
            delay(400)
            result = ShizukuManager.exec(context, cmd, SHELL_TIMEOUT_MS)
            forceStopped = true
        }
        val merged = buildJsonObject {
            result.forEach { (key, value) -> put(key, value) }
            if (forceStopped) {
                put("force_stopped", true)
                put(
                    "note",
                    "The app was already running on another display; it was force-stopped so a " +
                        "fresh task could start on display $displayId. Unsaved state in that app is lost."
                )
            }
        }
        shizukuResultToParts(merged)
    }
)

fun virtualDisplayTapTool(context: Context): Tool = Tool(
    name = "virtual_display_tap",
    description = """
        Tap at coordinates on a virtual display. Requires Shizuku (uses `input -d <id> tap`).
        Use take_screenshot(display_id=N) or read_window_tree(display_id=N) to see the result.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Virtual display id")
                })
                put("x", buildJsonObject {
                    put("type", "integer")
                    put("description", "X coordinate in pixels")
                })
                put("y", buildJsonObject {
                    put("type", "integer")
                    put("description", "Y coordinate in pixels")
                })
            },
            required = listOf("display_id", "x", "y")
        )
    },
    execute = { input ->
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull
        val x = input.jsonObject["x"]?.jsonPrimitive?.intOrNull
        val y = input.jsonObject["y"]?.jsonPrimitive?.intOrNull
        if (displayId == null || x == null || y == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_id, x, y are required") }.toString()
            ))
        }
        if (!VirtualDisplayController.contains(displayId)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_not_found") }.toString()
            ))
        }
        val cmd = buildTapCommand(displayId, x, y)
        val result = ShizukuManager.exec(context, cmd, SHELL_TIMEOUT_MS)
        shizukuResultToParts(result)
    }
)

fun virtualDisplaySwipeTool(context: Context): Tool = Tool(
    name = "virtual_display_swipe",
    description = """
        Swipe on a virtual display. Default mode=precise uses a slow ease-out gesture and
        stationary end samples to reduce fling; duration defaults to 900ms (minimum 600ms).
        Near a target, use short strokes and re-read take_screenshot(display_id=N) after EVERY
        stroke before deciding the next one. Gesture pixels are NOT page scroll pixels.
        mode=fling explicitly opts into fast system input; do not use it for precision.
        Precise mode requires a privileged VD service; no silent fast fallback.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Virtual display id")
                })
                put("x1", buildJsonObject { put("type", "integer"); put("description", "Start X") })
                put("y1", buildJsonObject { put("type", "integer"); put("description", "Start Y") })
                put("x2", buildJsonObject { put("type", "integer"); put("description", "End X") })
                put("y2", buildJsonObject { put("type", "integer"); put("description", "End Y") })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("precise"))
                        add(kotlinx.serialization.json.JsonPrimitive("fling"))
                    })
                    put("description", "precise (default, reduce overshoot) or explicit fling")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Duration in ms: precise default 900, clamped 600..3000; fling default 300, range 50..3000")
                })
            },
            required = listOf("display_id", "x1", "y1", "x2", "y2")
        )
    },
    execute = { input ->
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull
        val x1 = input.jsonObject["x1"]?.jsonPrimitive?.intOrNull
        val y1 = input.jsonObject["y1"]?.jsonPrimitive?.intOrNull
        val x2 = input.jsonObject["x2"]?.jsonPrimitive?.intOrNull
        val y2 = input.jsonObject["y2"]?.jsonPrimitive?.intOrNull
        val durationMs = input.jsonObject["duration_ms"]?.jsonPrimitive?.intOrNull
        if (displayId == null || x1 == null || y1 == null || x2 == null || y2 == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_id, x1, y1, x2, y2 are required") }.toString()
            ))
        }
        if (!VirtualDisplayController.contains(displayId)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_not_found") }.toString()
            ))
        }
        val mode = input.jsonObject["mode"]?.jsonPrimitive?.contentOrNull ?: "precise"
        if (mode !in setOf("precise", "fling")) {
            return@Tool shizukuResultToParts(buildJsonObject { put("error", "invalid_swipe_mode") })
        }
        val info = VirtualDisplayController.list().firstOrNull { it.displayId == displayId }
        if (info == null || x1 !in 0 until info.width || x2 !in 0 until info.width ||
            y1 !in 0 until info.height || y2 !in 0 until info.height) {
            return@Tool shizukuResultToParts(buildJsonObject { put("error", "coordinates_out_of_bounds") })
        }
        if (mode == "fling") {
            val cmd = buildSwipeCommand(displayId, x1, y1, x2, y2, (durationMs ?: 300).coerceIn(50, 3000))
            return@Tool shizukuResultToParts(ShizukuManager.exec(context, cmd, SHELL_TIMEOUT_MS))
        }
        val service = VirtualDisplayLauncher.currentService()
            ?: return@Tool shizukuResultToParts(buildJsonObject {
                put("error", "precise_swipe_unavailable")
                put("detail", "Requires a running privileged VD service. No gesture sent; do not retry as fling for precise positioning.")
            })
        val effectiveDuration = (durationMs ?: 900).coerceIn(600, 3000)
        val result = withContext(Dispatchers.IO) {
            runCatching { service.preciseSwipe(displayId, x1, y1, x2, y2, effectiveDuration) }
        }
        shizukuResultToParts(buildJsonObject {
            put("success", result.getOrDefault(false))
            put("verified", false)
            put("display_id", displayId)
            put("mode", mode)
            put("duration_ms", effectiveDuration)
            if (result.isFailure) put("error", "precise_swipe_failed")
            put("next_step", "Read take_screenshot(display_id=$displayId) before any next swipe; page displacement is not guaranteed. On failure a partial gesture may already have occurred: inspect, do not blindly repeat.")
        })
    }
)

fun virtualDisplayKeyTool(context: Context): Tool = Tool(
    name = "virtual_display_key",
    description = """
        Send a key event (e.g. Back=4, Home=3, Enter=66) to a virtual display. Requires
        Shizuku (uses `input -d <id> keyevent`). See Android KeyEvent constants.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Virtual display id")
                })
                put("keycode", buildJsonObject {
                    put("type", "integer")
                    put("description", "Android KeyEvent code (e.g. 4=Back, 3=Home, 66=Enter, 27=Camera)")
                })
            },
            required = listOf("display_id", "keycode")
        )
    },
    execute = { input ->
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull
        val keycode = input.jsonObject["keycode"]?.jsonPrimitive?.intOrNull
        if (displayId == null || keycode == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_id and keycode are required") }.toString()
            ))
        }
        if (!VirtualDisplayController.contains(displayId)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_not_found") }.toString()
            ))
        }
        val cmd = buildKeyCommand(displayId, keycode)
        val result = ShizukuManager.exec(context, cmd, SHELL_TIMEOUT_MS)
        shizukuResultToParts(result)
    }
)

fun virtualDisplayTextTool(context: Context): Tool = Tool(
    name = "virtual_display_text",
    description = """
        Insert Unicode text (Chinese, emoji, etc.) into the focused editor on a virtual display.
        Uses an accessibility InputConnection on Android 13+, even without an exposed window
        tree. Requires active accessibility service and Shizuku to verify VD input focus.
        Does not switch the user's keyboard or use a clipboard. Click the VD editor first.
        If focus/session cannot be verified, fails without sending text. Submission is NOT
        delivery confirmation: verify take_screenshot(display_id=N); do not blindly retry.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Virtual display id")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to type into the focused field")
                })
            },
            required = listOf("display_id", "text")
        )
    },
    execute = { input ->
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull
        val text = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        if (displayId == null || text == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_id and text are required") }.toString()
            ))
        }
        if (!VirtualDisplayController.contains(displayId)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "display_not_found") }.toString()
            ))
        }
        if (text.isEmpty()) {
            return@Tool shizukuResultToParts(buildJsonObject { put("error", "empty_text") })
        }
        shizukuResultToParts(VirtualDisplayUnicodeInput.commit(context, displayId, text))
    }
)
