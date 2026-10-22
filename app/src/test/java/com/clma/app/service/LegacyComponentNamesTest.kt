package com.clma.app.service

import com.clma.app.utils.AppIconStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the component names that must survive the me.rerere.rikkahub -> com.clma.app package
 * move. Android stores accessibility / notification-access grants and the enabled launcher
 * alias by component name; if any of these drift, existing users silently lose the grant or
 * their icon choice after updating.
 */
class LegacyComponentNamesTest {

    private val manifest: String = listOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).first { it.isFile }.readText()

    @Test
    fun `accessibility service keeps its pre-rename component name`() {
        assertEquals(
            "me.rerere.rikkahub.service.RikkaAccessibilityService",
            LegacyComponentNames.ACCESSIBILITY_SERVICE,
        )
        assertTrue(
            RikkaAccessibilityService::class.java.isAssignableFrom(
                me.rerere.rikkahub.service.RikkaAccessibilityService::class.java
            )
        )
        assertTrue(manifest.contains("android:name=\"${LegacyComponentNames.ACCESSIBILITY_SERVICE}\""))
    }

    @Test
    fun `notification listener keeps its pre-rename component name`() {
        assertEquals(
            "me.rerere.rikkahub.service.RikkaNotificationListenerService",
            LegacyComponentNames.NOTIFICATION_LISTENER,
        )
        assertTrue(
            RikkaNotificationListenerService::class.java.isAssignableFrom(
                me.rerere.rikkahub.service.RikkaNotificationListenerService::class.java
            )
        )
        assertTrue(manifest.contains("android:name=\"${LegacyComponentNames.NOTIFICATION_LISTENER}\""))
    }

    @Test
    fun `launcher aliases keep their pre-rename names and match the manifest`() {
        assertEquals("me.rerere.rikkahub.Launcher_Default", AppIconStyle.DEFAULT.aliasName)
        assertEquals("me.rerere.rikkahub.Launcher_Ring", AppIconStyle.RING.aliasName)
        AppIconStyle.entries.forEach { style ->
            assertTrue(style.aliasName, manifest.contains("android:name=\"${style.aliasName}\""))
        }
    }

    @Test
    fun `the new-package service classes are not declared directly`() {
        // Declaring the implementation class as well would register a second, never-enabled
        // service in system settings.
        assertTrue(!manifest.contains("android:name=\".service.RikkaAccessibilityService\""))
        assertTrue(!manifest.contains("android:name=\"com.clma.app.service.RikkaAccessibilityService\""))
        assertTrue(!manifest.contains("android:name=\".service.RikkaNotificationListenerService\""))
        assertTrue(!manifest.contains("android:name=\"com.clma.app.service.RikkaNotificationListenerService\""))
    }
}
