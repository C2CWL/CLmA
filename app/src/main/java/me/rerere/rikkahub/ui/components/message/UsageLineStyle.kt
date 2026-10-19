package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.pages.chat.PHOTO_TITLE_PLATE_ALPHA

/*
 * Styling of the per-turn usage line under an assistant reply. Pure (no Compose / Android types)
 * so every branch is unit-tested.
 *
 * The frosted look mirrors the general frosted plate of the frosted-title PR field for field
 * (`FrostedPlateSpec(tintAlpha, blurRadiusDp)`, `frostedPlateSpec(photoShown, sdkInt)`, 0.70 tint,
 * 20dp blur, API 31+). It is kept local only so this PR stands alone; whichever of the two lands
 * second replaces [UsageFrostSpec] / [usageFrostSpec] with the shared helper and the
 * `Modifier.frostedPlate` modifier (passing the wallpaper-only source selection).
 */

/** Which theme colour the usage line is drawn in. */
internal enum class UsageLineInk { SECONDARY, ON_SURFACE }

/** A frosted pill: the wallpaper under it is blurred by [blurRadiusDp], then tinted at [tintAlpha]. */
internal data class UsageFrostSpec(val tintAlpha: Float, val blurRadiusDp: Float)

/** Tint over the blurred wallpaper. Same value as the shared frosted plate. */
internal const val USAGE_FROST_TINT_ALPHA = 0.70f

/** Wallpaper blur radius. Same value as the shared frosted plate. */
internal const val USAGE_FROST_BLUR_DP = 20f

/** First API level where Haze blurs for real (RenderEffect); below it Haze only draws a scrim. */
internal const val USAGE_FROST_MIN_SDK = 31

/**
 * The frosted spec when a photo is shown, the device can blur and the page provided its haze
 * state; otherwise null and the caller uses the flat plate (or none).
 */
internal fun usageFrostSpec(photoShown: Boolean, sdkInt: Int, hasHazeState: Boolean): UsageFrostSpec? =
    if (photoShown && hasHazeState && sdkInt >= USAGE_FROST_MIN_SDK) {
        UsageFrostSpec(tintAlpha = USAGE_FROST_TINT_ALPHA, blurRadiusDp = USAGE_FROST_BLUR_DP)
    } else {
        null
    }

/**
 * [plateAlpha] is the flat `surface` plate behind the line (null for none); [frost] replaces it
 * with a frosted pill when non-null. At most one of the two is set.
 */
internal data class UsageLineStyle(
    val ink: UsageLineInk,
    val inkAlpha: Float,
    val plateAlpha: Float? = null,
    val frost: UsageFrostSpec? = null,
)

/**
 * Off a photo the line stays the quiet `secondary@0.5`. On a photo it uses neutral onSurface text
 * (a tinted `secondary` clashes with sky / sea / flower photos), in both themes:
 *
 *  - photo + API 31+ (with haze state): a frosted pill. Blurring removes the texture that made
 *    the text hard to read, so the lighter `surface@0.70` tint is enough, with stronger ink.
 *  - photo + older API (or no haze state): the flat title plate, `surface@0.86`.
 */
internal fun usageLineStyle(photoShown: Boolean, sdkInt: Int, hasHazeState: Boolean): UsageLineStyle {
    if (!photoShown) return UsageLineStyle(UsageLineInk.SECONDARY, inkAlpha = 0.5f)
    val frost = usageFrostSpec(photoShown = true, sdkInt = sdkInt, hasHazeState = hasHazeState)
    return if (frost != null) {
        UsageLineStyle(UsageLineInk.ON_SURFACE, inkAlpha = 0.85f, frost = frost)
    } else {
        UsageLineStyle(UsageLineInk.ON_SURFACE, inkAlpha = 0.72f, plateAlpha = PHOTO_TITLE_PLATE_ALPHA)
    }
}
