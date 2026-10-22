package me.rerere.rikkahub.service

/**
 * Legacy-name shim. Do NOT move or rename this class.
 *
 * The app's sources moved from `me.rerere.rikkahub` to `com.clma.app`, but Android remembers
 * notification-access grants by component name (Settings.Secure enabled_notification_listeners).
 * Declaring the listener under its old class name keeps an existing grant working across the
 * update. All behaviour lives in [com.clma.app.service.RikkaNotificationListenerService]; see
 * [com.clma.app.service.LegacyComponentNames].
 */
class RikkaNotificationListenerService : com.clma.app.service.RikkaNotificationListenerService()
