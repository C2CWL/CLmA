package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How popup / dropdown menus are drawn.
 *
 * [CLASSIC] is the stock Material3 menu (flat corners, plain fade).
 * [EXPRESSIVE] uses M3 Expressive popup + grouped item shapes (Strata-style).
 *
 * Default is [EXPRESSIVE]; settings written before this field existed also read
 * back as expressive so the new look lands without an extra toggle flip.
 */
@Serializable
enum class PopupMenuStyle {
    @SerialName("classic")
    CLASSIC,

    @SerialName("expressive")
    EXPRESSIVE,
}
