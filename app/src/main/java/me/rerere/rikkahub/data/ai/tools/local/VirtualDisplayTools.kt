package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.shizuku.ShizukuManager

// Pure helpers (validateCreateParams, build*Command) live in VirtualDisplayRules.kt
// so they can be unit-tested without Android dependencies.

private const val SHELL_TIMEOUT_MS = 10_000

/** Wraps a Shizuku exec result into a tool result (list of UIMessagePart.Text). */
private fun shizukuResultToParts(result: JsonObject): List<UIMessagePart.Text> =
    listOf(UIMessagePart.Text(result.toString()))

// --- tool definitions --------------------------------------------------------------------

/**
 * Create a virtual display that runs apps off-screen. The agent can launch apps on it,
 * inject touch/input, and screenshot it via `take_screenshot(display_id=N)` — all without
 * stealing the foreground from the user.
 *
 * Requires no special permission for creation (uses DisplayManager public API). Input
 * injection and app launching need Shizuku (shell UID). Screenshots use the existing
 * AccessibilityService `take_screenshot` tool with `display_id`.
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
                    put("hint", "Use take_screenshot(display_id=${result.value}) to capture this display")
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
    description = "Destroy a virtual display by its display_id. Releases the surface and resources.",
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

fun virtualDisplayLaunchTool(context: Context): Tool = Tool(
    name = "virtual_display_launch",
    description = """
        Launch an app on a virtual display. Requires Shizuku (shell UID) to run `am start
        --display`. Use the package_name from list_installed_apps; optionally specify an
        activity for a specific screen.
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
                    put("description", "Optional activity class name to launch a specific screen")
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
        if (!VirtualDisplayController.contains(displayId)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("error", "display_not_found")
                    put("detail", "Create the display first with virtual_display_create")
                }.toString()
            ))
        }
        val cmd = buildLaunchCommand(displayId, pkg, activity)
        val result = ShizukuManager.exec(context, cmd, SHELL_TIMEOUT_MS)
        shizukuResultToParts(result)
    }
)

fun virtualDisplayTapTool(context: Context): Tool = Tool(
    name = "virtual_display_tap",
    description = """
        Tap at coordinates on a virtual display. Requires Shizuku (uses `input -d <id> tap`).
        Use take_screenshot(display_id=N) to see the result.
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
        Swipe between two points on a virtual display. Requires Shizuku (uses `input -d <id>
        swipe`). duration_ms is optional (default ~300ms). Use take_screenshot(display_id=N)
        to verify the result.
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
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Swipe duration in ms (optional, default 300)")
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
        val cmd = buildSwipeCommand(displayId, x1, y1, x2, y2, durationMs)
        val result = ShizukuManager.exec(context, cmd, SHELL_TIMEOUT_MS)
        shizukuResultToParts(result)
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
        Type text into the focused field on a virtual display. Requires Shizuku (uses `input
        -d <id> text`). Text is shell-escaped. Note: this injects text, not IME — it works
        for EditText fields but not for IME-dependent input methods.
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
        val cmd = buildTextCommand(displayId, text)
        val result = ShizukuManager.exec(context, cmd, SHELL_TIMEOUT_MS)
        shizukuResultToParts(result)
    }
)
