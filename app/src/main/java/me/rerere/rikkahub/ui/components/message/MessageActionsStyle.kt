package me.rerere.rikkahub.ui.components.message

/*
 * Styling of the action row under a message, assistant and user alike (copy, regenerate, TTS,
 * translate, more, branch selector, timestamp). Pure (no Compose / Android types) so every
 * branch is unit-tested.
 *
 * Over a photo wallpaper it gets the same plate as the per-turn usage line ([usageLineStyle]):
 * a frosted pill on API 31+ with the page's haze state, otherwise the flat title plate, and
 * neutral onSurface ink. Off a photo nothing changes.
 */

/**
 * [onSurfaceInk] switches icons and text from the default `onSurfaceVariant` to `onSurface`.
 * [plateAlpha] / [frost] are the usage line's plate (at most one set; both null = no plate).
 */
internal data class MessageActionsStyle(
    val onSurfaceInk: Boolean,
    val iconAlpha: Float,
    val timeAlpha: Float,
    val plateAlpha: Float? = null,
    val frost: UsageFrostSpec? = null,
) {
    val plated: Boolean get() = plateAlpha != null || frost != null
}

/** Off a photo: the original look (icons at full `onSurfaceVariant`, the time at 0.6). */
internal val DEFAULT_MESSAGE_ACTIONS_STYLE = MessageActionsStyle(onSurfaceInk = false, iconAlpha = 1f, timeAlpha = 0.6f)

/** Icons over a plate: a touch stronger than the usage text, they are thin strokes. */
internal const val MESSAGE_ACTIONS_PLATED_ICON_ALPHA = 0.9f

internal fun messageActionsStyle(photoShown: Boolean, sdkInt: Int, hasHazeState: Boolean): MessageActionsStyle {
    if (!photoShown) return DEFAULT_MESSAGE_ACTIONS_STYLE
    val usage = usageLineStyle(photoShown = true, sdkInt = sdkInt, hasHazeState = hasHazeState)
    return MessageActionsStyle(
        onSurfaceInk = true,
        iconAlpha = MESSAGE_ACTIONS_PLATED_ICON_ALPHA,
        // The timestamp reads like the usage line under it, at the same ink.
        timeAlpha = usage.inkAlpha,
        plateAlpha = usage.plateAlpha,
        frost = usage.frost,
    )
}
