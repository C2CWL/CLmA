package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which loading indicator the chat shows while the assistant is thinking / generating. */
@Serializable
enum class LoadingIndicatorStyle {
    /** Material contained loading indicator (the old switch turned off). */
    @SerialName("default")
    DEFAULT,

    /** Animated app-icon rabbit (the old switch turned on, and the long-standing default). */
    @SerialName("app_icon")
    APP_ICON,

    /** A soft light sweeping across a "Thinking…" label. */
    @SerialName("shimmer")
    SHIMMER,

    /**
     * A glossy gradient pentagon on a faint disc, with a comet ring, twinkling stars and a
     * shimmering "Replying…" label. Left-aligned in the chat, where the reply text starts.
     */
    @SerialName("sparkle")
    SPARKLE,
}

/**
 * Effective style. [style] is null until the user picks one in the new selector, so existing
 * installs keep whatever the old [useAppIconStyle] switch said.
 */
fun resolveLoadingIndicatorStyle(style: LoadingIndicatorStyle?, useAppIconStyle: Boolean): LoadingIndicatorStyle =
    style ?: if (useAppIconStyle) LoadingIndicatorStyle.APP_ICON else LoadingIndicatorStyle.DEFAULT

/**
 * Applies a selector pick. The legacy switch is kept in step for the two styles it can express,
 * so an older build reading the same settings still shows the closest thing; SHIMMER and SPARKLE
 * leave it.
 */
fun DisplaySetting.withLoadingIndicatorStyle(style: LoadingIndicatorStyle): DisplaySetting = copy(
    loadingIndicatorStyle = style,
    useAppIconStyleLoadingIndicator = when (style) {
        LoadingIndicatorStyle.APP_ICON -> true
        LoadingIndicatorStyle.DEFAULT -> false
        LoadingIndicatorStyle.SHIMMER, LoadingIndicatorStyle.SPARKLE -> useAppIconStyleLoadingIndicator
    },
)

val DisplaySetting.effectiveLoadingIndicatorStyle: LoadingIndicatorStyle
    get() = resolveLoadingIndicatorStyle(loadingIndicatorStyle, useAppIconStyleLoadingIndicator)
