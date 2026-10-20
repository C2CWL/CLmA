package me.rerere.rikkahub.data.ai.tools.local

/**
 * Pure-logic command builders and parameter validators for virtual display tools.
 *
 * Extracted from [VirtualDisplayTools] so they can be unit-tested on a bare JVM (no Android,
 * no Shizuku, no ai module) via `kcheck`. The tool factories in [VirtualDisplayTools] delegate
 * here for all shell-command construction and input validation.
 */

/**
 * `am` intent flags used for every launch onto a virtual display:
 * `FLAG_ACTIVITY_NEW_TASK (0x10000000) | FLAG_ACTIVITY_MULTIPLE_TASK (0x08000000)`.
 *
 * `--display` is only honoured when a *new* task is created. Without these flags `am` hands
 * the intent to the app's existing top-most instance and prints
 * "Warning: Activity not started, intent has been delivered to currently running top-most
 * instance." — the task is then moved onto the virtual display in a paused, `visible=false`
 * state, so nothing renders (no frames) and the accessibility service has no window to read.
 * Verified on device (Android 14): fresh task => `mHasContent=true`; reused task => task on
 * the display but `visible=false`.
 */
internal const val LAUNCH_INTENT_FLAGS = "0x18000000"

/** Java package name: dot-separated identifiers. */
private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

/** Activity class name, absolute or relative (".MainActivity"). */
private val ACTIVITY_NAME = Regex("[A-Za-z0-9_.$]+")

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

/**
 * True when [packageName] is a plain Java package name.
 *
 * Everything here is interpolated into a shell command run as shell UID, so a package name
 * containing spaces, `;`, `$()` or quotes must be rejected rather than passed through.
 */
internal fun isValidPackageName(packageName: String?): Boolean =
    packageName != null && PACKAGE_NAME.matches(packageName)

/**
 * True when [activity] is a class name usable after `-n pkg/`. `null` means "no activity
 * given" and is valid (the launcher activity is resolved implicitly).
 */
internal fun isValidActivityName(activity: String?): Boolean =
    activity == null || ACTIVITY_NAME.matches(activity)

/**
 * Builds the `am start --display` command for launching an app on a virtual display.
 *
 * With [activity] the component is `pkg/act` (a relative name like ".MainActivity" is fine).
 * Without it, `-n <pkg>` would be rejected by `am` ("Bad component name: <pkg>") because a
 * component name must contain a slash — so an implicit MAIN/LAUNCHER intent scoped to the
 * package is used instead, which resolves to the app's launcher activity.
 */
internal fun buildLaunchCommand(displayId: Int, packageName: String, activity: String?): String {
    val flags = "-f $LAUNCH_INTENT_FLAGS"
    return if (activity != null) {
        "am start --display $displayId $flags -n $packageName/$activity"
    } else {
        "am start --display $displayId $flags " +
            "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName"
    }
}

/** Builds the `am force-stop` command used before retrying a launch that was delivered to an existing task. */
internal fun buildForceStopCommand(packageName: String): String = "am force-stop $packageName"

/**
 * True when `am start` output says the intent was handed to an already-running instance
 * instead of creating a task on the target display.
 *
 * Matches the AOSP wording plus the shorter OEM variant; deliberately does *not* match
 * "unable to resolve Intent", which is a different failure (bad package/activity).
 */
internal fun wasDeliveredToRunningInstance(amOutput: String): Boolean =
    amOutput.contains("delivered to currently running top-most instance") ||
        amOutput.contains("delivered to top-most instance")

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