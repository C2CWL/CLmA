package me.rerere.rikkahub.service

/**
 * Legacy-name shim. Do NOT move or rename this class.
 *
 * The app's sources moved from `me.rerere.rikkahub` to `com.clma.app`, but Android remembers
 * which accessibility services the user switched on by component name
 * (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES). Declaring the service under its old class
 * name keeps an existing grant working across the update. All behaviour lives in
 * [com.clma.app.service.RikkaAccessibilityService]; see [com.clma.app.service.LegacyComponentNames].
 */
class RikkaAccessibilityService : com.clma.app.service.RikkaAccessibilityService()
