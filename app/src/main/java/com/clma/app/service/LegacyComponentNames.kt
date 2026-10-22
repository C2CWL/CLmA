package com.clma.app.service

import android.content.ComponentName
import android.content.Context

/**
 * Component names that must keep their pre-rename (me.rerere.rikkahub.*) class names.
 *
 * Android keys several user grants by the *component* name, not the class that implements
 * it: the accessibility-service toggle (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), the
 * notification-access toggle (enabled_notification_listeners) and the enabled launcher
 * activity-alias. Renaming those components would silently switch the user's grants off /
 * reset the icon after an update, so the manifest keeps declaring them under the old names:
 *
 *  - the two services are thin subclasses living in `me.rerere.rikkahub.service`
 *    ([me.rerere.rikkahub.service.RikkaAccessibilityService],
 *    [me.rerere.rikkahub.service.RikkaNotificationListenerService]) that extend the real
 *    implementations in this package;
 *  - the launcher aliases are plain `activity-alias` names (see AppIconManager).
 *
 * Every place that builds or compares one of these components must go through here.
 */
object LegacyComponentNames {
    /** Manifest component name of the accessibility service (old FQCN, kept on purpose). */
    val ACCESSIBILITY_SERVICE: String =
        me.rerere.rikkahub.service.RikkaAccessibilityService::class.java.name

    /** Manifest component name of the notification listener (old FQCN, kept on purpose). */
    val NOTIFICATION_LISTENER: String =
        me.rerere.rikkahub.service.RikkaNotificationListenerService::class.java.name

    fun accessibilityService(context: Context): ComponentName =
        ComponentName(context.packageName, ACCESSIBILITY_SERVICE)

    fun notificationListener(context: Context): ComponentName =
        ComponentName(context.packageName, NOTIFICATION_LISTENER)
}
