package com.clma.app.data.ai

import android.content.Context
import android.content.pm.PackageManager

/** Per-resolution dependency snapshot adapted from moxw (AGPL-3.0, 81bbb7e). */
internal data class InstanceRuntimeSnapshot(
    val termux: Presence = Presence.UNKNOWN,
    val shizuku: Presence = Presence.UNKNOWN,
) {
    enum class Presence { PRESENT, ABSENT, UNKNOWN }

    fun unavailableToolNames(): Set<String> = buildSet {
        if (termux == Presence.ABSENT) addAll(listOf(
            "termux_run_command", "termux_session_start", "termux_session_send",
            "termux_session_read", "termux_session_kill", "termux_session_list", "transcribe_audio_file",
        ))
        if (shizuku == Presence.ABSENT) addAll(listOf(
            "shizuku_exec", "virtual_display_launch", "virtual_display_tap",
            "virtual_display_swipe", "virtual_display_key", "virtual_display_text",
        ))
    }

    companion object {
        fun capture(context: Context, checkTermux: Boolean, checkShizuku: Boolean): InstanceRuntimeSnapshot {
            // Both packages are declared in <queries>; other failures mean unknown, not absent.
            fun presence(packageName: String): Presence = try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
                Presence.PRESENT
            } catch (_: PackageManager.NameNotFoundException) {
                Presence.ABSENT
            } catch (_: RuntimeException) {
                Presence.UNKNOWN
            }
            return InstanceRuntimeSnapshot(
                termux = if (checkTermux) presence("com.termux") else Presence.UNKNOWN,
                shizuku = if (checkShizuku) presence("moe.shizuku.privileged.api") else Presence.UNKNOWN,
            )
        }
    }
}
