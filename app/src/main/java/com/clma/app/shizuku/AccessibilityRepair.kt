package me.rerere.rikkahub.shizuku

/** Wall-clock budget for [buildAccessibilityRepairCommand]: a handful of local
 *  `settings` / `content` calls plus one `am force-stop`. */
const val ACCESSIBILITY_REPAIR_TIMEOUT_MS = 15_000

/**
 * Merge [component] into a `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` value: every other
 * entry is kept, order is preserved, and ours is appended only when it is missing (so the call
 * is idempotent and never stomps a second enabled service).
 *
 * An unset key reaches us as `null` through the provider API and as the literal string `"null"`
 * from `settings get`; both, plus blank, mean "nothing enabled yet".
 *
 * Pure on purpose - the same rule is applied in shell by [buildAccessibilityRepairCommand], and
 * this is the copy that runs under unit test without a device.
 */
fun mergeEnabledAccessibilityServices(current: String?, component: String): String {
    val base = current?.trim().orEmpty().takeIf { it.isNotEmpty() && it != "null" }.orEmpty()
    if (base.split(':').any { it.equals(component, ignoreCase = true) }) return base
    return if (base.isEmpty()) component else "$base:$component"
}

/**
 * Build the shell command that (re)adds [component] to the enabled-accessibility list and turns
 * `accessibility_enabled` back on, executed through [ShizukuManager.exec] as the shell UID.
 * Used by Settings -> Accessibility -> "one-tap repair".
 *
 * Two write paths on purpose. `settings put` is the canonical one, but some OEM builds answer it
 * with exit code 0 while the value never lands in the provider (observed on vivo/OriginOS 16),
 * whereas `content insert` goes through SettingsProvider's normal insert path, which upserts by
 * key. Both are idempotent, so running both is safe and the fallback costs nothing when
 * `settings put` works.
 *
 * The trailing `am force-stop com.android.settings` is the actual ROM workaround: once the
 * system Accessibility page has been opened, the Settings process holds its own copy of the
 * enabled list and writes that copy back over ours on a later lifecycle event - which is exactly
 * what makes the toggle look like it "springs back to off" after you leave that page. Killing
 * the process clears that stale copy; Settings starts again on demand, so the only cost is a
 * reopen. Ordering matters: the kill runs *after* the writes, so a rewrite it had already queued
 * cannot land on top of ours.
 *
 * The last statement echoes the resulting value as a cheap trace. Callers must still re-read
 * [me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle.isEnabledInSettings] to
 * decide whether the repair actually took: this command cannot report that reliably, because the
 * whole point of the fallback is that a "successful" write may have been dropped.
 */
fun buildAccessibilityRepairCommand(component: String): String = listOf(
    "cur=\$(settings get secure enabled_accessibility_services)",
    "if [ \"\$cur\" = \"null\" ]; then cur=\"\"; fi",
    "case \":\$cur:\" in",
    "  *\":${component}:\"*) ;;",
    "  *) if [ -z \"\$cur\" ]; then cur=\"${component}\"; else cur=\"\$cur:${component}\"; fi ;;",
    "esac",
    "settings put secure enabled_accessibility_services \"\$cur\" >/dev/null 2>&1",
    "content insert --uri content://settings/secure --bind name:s:enabled_accessibility_services --bind value:s:\"\$cur\" >/dev/null 2>&1",
    "settings put secure accessibility_enabled 1 >/dev/null 2>&1",
    "content insert --uri content://settings/secure --bind name:s:accessibility_enabled --bind value:s:1 >/dev/null 2>&1",
    "am force-stop com.android.settings >/dev/null 2>&1",
    "settings get secure enabled_accessibility_services",
).joinToString("\n")
