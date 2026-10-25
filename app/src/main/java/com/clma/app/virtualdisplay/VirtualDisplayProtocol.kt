package com.clma.app.virtualdisplay

internal object VirtualDisplayProtocol {
    const val EXTRA_TOKEN = "token"
    const val EXTRA_BINDER = "binder"

    /**
     * Only shell and root hold this, so no third-party app can answer the launcher's broadcast
     * receiver with a binder of its own.
     */
    const val SENDER_PERMISSION = "android.permission.INTERACT_ACROSS_USERS_FULL"

    /** Matches android.os.UserHandle.PER_USER_RANGE. */
    const val PER_USER_RANGE = 100_000

    fun readyAction(packageName: String): String = "$packageName.virtualdisplay.SERVICE_READY"

    // VirtualDisplay private flags (from AOSP DisplayManager.java)
    const val FLAG_PUBLIC = 1 shl 0
    const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val FLAG_TRUSTED = 1 shl 10
    const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val FLAG_OWN_FOCUS = 1 shl 14
    const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    fun agentVirtualDisplayFlags(): Int =
        FLAG_PUBLIC or
            FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or
            FLAG_TRUSTED or
            FLAG_TOUCH_FEEDBACK_DISABLED or
            FLAG_OWN_FOCUS or
            FLAG_STEAL_TOP_FOCUS_DISABLED
}
