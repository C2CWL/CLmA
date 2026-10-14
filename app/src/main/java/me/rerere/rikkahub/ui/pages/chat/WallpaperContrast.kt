package me.rerere.rikkahub.ui.pages.chat

/**
 * A custom chat photo sits under theme-coloured UI. The old veil painted
 * `colorScheme.background` at alpha 0.2 → 0.5 across the whole picture.
 *
 * In the dark theme that veil is near-black, so a dark photo is unchanged and the
 * light text stays readable. The same alphas in the light theme are a white fog,
 * which is why one wallpaper looks vivid at night and washed out by day.
 *
 * The light theme therefore does not veil the photo. Contrast moves onto the text
 * itself: a plate behind bare assistant replies and under the title. The bar fade
 * stays a dissolve for the status icons, not a coat of paint.
 */

internal fun chatPhotoShown(background: String?, useGradientBackground: Boolean): Boolean =
    background != null && !useGradientBackground

internal data class WallpaperScrim(val topAlpha: Float, val bottomAlpha: Float)

/** Dark theme keeps the historical veil. Light theme leaves the photo alone. */
internal fun wallpaperScrim(darkTheme: Boolean): WallpaperScrim =
    if (darkTheme) WallpaperScrim(topAlpha = 0.20f, bottomAlpha = 0.50f)
    else WallpaperScrim(topAlpha = 0f, bottomAlpha = 0f)

internal data class TitleBarFade(val topAlpha: Float, val midAlpha: Float, val bottomAlpha: Float)

internal fun titleBarFade(darkTheme: Boolean, photoShown: Boolean): TitleBarFade =
    if (!darkTheme && photoShown) {
        // Status-bar icons are dark in the light theme, so the very top stays mostly
        // covered. The title has its own plate, and the picture is clear by the bottom
        // edge of the bar.
        TitleBarFade(topAlpha = 0.78f, midAlpha = 0.36f, bottomAlpha = 0f)
    } else {
        TitleBarFade(topAlpha = 1f, midAlpha = 0.75f, bottomAlpha = 0f)
    }

/** Plate behind assistant text that opted out of a bubble. Opaque enough to read. */
internal const val PHOTO_TEXT_PLATE_ALPHA = 0.92f

/** Plate under the title. Slightly clearer so the photo still shows through. */
internal const val PHOTO_TITLE_PLATE_ALPHA = 0.86f

/** Any loose onSurface text (title, status line) drawn straight on a light-theme photo. */
internal fun looseTextNeedsPhotoPlate(darkTheme: Boolean, photoShown: Boolean): Boolean =
    !darkTheme && photoShown

/** Bare assistant text. A real bubble already provides the plate. */
internal fun assistantTextNeedsPhotoPlate(
    darkTheme: Boolean,
    photoShown: Boolean,
    showAssistantBubble: Boolean,
): Boolean = looseTextNeedsPhotoPlate(darkTheme, photoShown) && !showAssistantBubble
