package me.rerere.rikkahub.data.permissions

/**
 * Which OEM power-management family the device belongs to.
 *
 * Several domestic ROMs (Vivo/OriginOS, Xiaomi/HyperOS, Huawei/HarmonyOS, OPPO/ColorOS and
 * their sub-brands) aggressively reap background processes. When the app is killed, the bound
 * AccessibilityService goes with it, so the screen-automation tools start failing with
 * "AccessibilityService not active" **even though the system toggle is still on**. There is no
 * API that prevents this; the only cure is walking the user through the vendor's allow-list
 * (autostart + background battery allow-list + lock in the recents list).
 *
 * Pure Kotlin (no Android imports) so it is unit-testable on the JVM.
 */
enum class KeepAliveVendor { VIVO, XIAOMI, HUAWEI, OPPO, GENERIC }

/**
 * Maps [android.os.Build.MANUFACTURER] / [android.os.Build.BRAND] to a vendor family.
 * Null-safe and case-insensitive; unknown/absent values fall back to [KeepAliveVendor.GENERIC].
 */
fun keepAliveVendorOf(manufacturer: String?, brand: String? = null): KeepAliveVendor {
    val hay = listOfNotNull(manufacturer, brand).joinToString(" ").lowercase()
    return when {
        "vivo" in hay || "iqoo" in hay -> KeepAliveVendor.VIVO
        "xiaomi" in hay || "redmi" in hay || "poco" in hay -> KeepAliveVendor.XIAOMI
        "huawei" in hay || "honor" in hay -> KeepAliveVendor.HUAWEI
        "oppo" in hay || "oneplus" in hay || "realme" in hay -> KeepAliveVendor.OPPO
        else -> KeepAliveVendor.GENERIC
    }
}

/**
 * True when this vendor is known to reap background apps hard enough that the accessibility
 * service can be dropped without any crash. Used to decide whether to surface the keep-alive
 * guidance proactively (settings page + first-run guide) instead of waiting for a failure.
 */
val KeepAliveVendor.isAggressive: Boolean
    get() = this != KeepAliveVendor.GENERIC
