package me.rerere.rikkahub.data.ai.tools.local

/**
 * Pure-logic command builders and parameter validators for virtual display tools.
 *
 * Extracted from [VirtualDisplayTools] so they can be unit-tested on a bare JVM (no Android,
 * no Shizuku, no ai module) via `kcheck`. The tool factories in [VirtualDisplayTools] delegate
 * here for all shell-command construction and input validation.
 */

/** Validates create parameters. Returns null if invalid. */
internal fun validateCreateParams(width: Int?, height: Int?, density: Int?): Triple<Int, Int, Int>? {
    val w = width
    val h = height
    val d = density ?: 320
    if (w == null || h == null) return null
    if (w < 1 || w > 4096) return null
    if (h < 1 || h > 4096) return null
    if (d < 1 || d > 640) return null
    return Triple(w, h, d)
}

/** Builds the `am start --display` command for launching an app on a virtual display. */
internal fun buildLaunchCommand(displayId: Int, packageName: String, activity: String?): String {
    val component = if (activity != null) "-n $packageName/$activity" else "-n $packageName"
    return "am start --display $displayId $component"
}

/** Builds the `input -d` tap command. */
internal fun buildTapCommand(displayId: Int, x: Int, y: Int): String =
    "input -d $displayId tap $x $y"

/** Builds the `input -d` swipe command. */
internal fun buildSwipeCommand(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int?): String =
    if (durationMs != null) "input -d $displayId swipe $x1 $y1 $x2 $y2 $durationMs"
    else "input -d $displayId swipe $x1 $y1 $x2 $y2"

/** Builds the `input -d` keyevent command. */
internal fun buildKeyCommand(displayId: Int, keycode: Int): String =
    "input -d $displayId keyevent $keycode"

/** Builds the `input -d` text command. Quotes the text to prevent shell injection. */
internal fun buildTextCommand(displayId: Int, text: String): String {
    // Escape single quotes: replace ' with '\'' (standard shell escaping)
    val escaped = text.replace("'", "'\\''")
    return "input -d $displayId text '$escaped'"
}
