package me.rerere.rikkahub.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Home-screen icon switching between the two `activity-alias` entries declared in the
 * manifest.
 *
 * Both aliases point at the same [me.rerere.rikkahub.RouteActivity], so this is a real
 * icon replacement, not a shortcut: the launcher entry itself is swapped and the old one
 * disappears from the home screen / app drawer.
 *
 * The swap enables the target alias first and only then disables the previous one. Doing
 * it the other way round briefly leaves the package with zero launcher entries, which
 * some launchers react to by dropping the icon from its saved layout. `DONT_KILL_APP`
 * keeps the running process alive, so switching does not look like a crash.
 */
enum class AppIconStyle(val aliasName: String) {
    /** F1 — stone-paper background, fluid orb, white C holding the Y. */
    DEFAULT("me.rerere.rikkahub.Launcher_Default"),

    /** Ring — pale cool background, closed ring with a blue node. */
    RING("me.rerere.rikkahub.Launcher_Ring"),
}

private const val TAG = "AppIconManager"

private fun aliasComponent(context: Context, style: AppIconStyle) =
    ComponentName(context.packageName, style.aliasName)

private fun PackageManager.isAliasEnabled(component: ComponentName): Boolean =
    when (getComponentEnabledSetting(component)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
        // COMPONENT_ENABLED_STATE_DEFAULT: fall back to what the manifest declares.
        else -> runCatching { getActivityInfo(component, 0).enabled }.getOrDefault(false)
    }

/** Which alias the launcher is currently showing. Falls back to [AppIconStyle.DEFAULT]. */
fun currentAppIcon(context: Context): AppIconStyle {
    val pm = context.packageManager
    return AppIconStyle.entries.firstOrNull { pm.isAliasEnabled(aliasComponent(context, it)) }
        ?: AppIconStyle.DEFAULT
}

/**
 * Switches the launcher icon to [style]. Safe to call when [style] is already active.
 *
 * @return true when the package manager accepted the change.
 */
fun applyAppIcon(context: Context, style: AppIconStyle): Boolean {
    val pm = context.packageManager
    val target = aliasComponent(context, style)
    val others = AppIconStyle.entries.filter { it != style }.map { aliasComponent(context, it) }

    return runCatching {
        // Enable the new alias first: the package always keeps one launcher entry.
        pm.setComponentEnabledSetting(
            target,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        others.forEach { component ->
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        true
    }.onFailure {
        Log.w(TAG, "Failed to switch app icon to ${style.name}", it)
    }.getOrDefault(false)
}