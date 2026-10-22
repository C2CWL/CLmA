package me.rerere.rikkahub.ui.components.ui

/**
 * A frosted plate over a photo wallpaper: the page behind it is blurred, then a translucent
 * theme tint is laid on top. Blur removes the photo's texture, so a light tint keeps text
 * readable where a flat plate had to be near-opaque.
 *
 * Pure (no Compose / Android types) so the values are unit-tested.
 */
internal data class FrostedPlateSpec(val tintAlpha: Float, val blurRadiusDp: Float)

/** Tint over the blurred backdrop. */
internal const val FROSTED_PLATE_TINT_ALPHA = 0.70f

/** Blur radius of the backdrop. Between the top-bar glass buttons (12dp) and the drawer (24dp). */
internal const val FROSTED_PLATE_BLUR_DP = 20f

/** First API level where Haze blurs for real (RenderEffect); below it Haze falls back to a scrim. */
internal const val FROSTED_PLATE_MIN_SDK = 31

/**
 * The frosted spec when a photo wallpaper is shown and the device can blur, otherwise null:
 * the caller keeps its current flat look.
 */
internal fun frostedPlateSpec(photoShown: Boolean, sdkInt: Int): FrostedPlateSpec? =
    if (photoShown && sdkInt >= FROSTED_PLATE_MIN_SDK) {
        FrostedPlateSpec(tintAlpha = FROSTED_PLATE_TINT_ALPHA, blurRadiusDp = FROSTED_PLATE_BLUR_DP)
    } else {
        null
    }
