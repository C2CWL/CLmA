package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How the settings **hub** is labelled and grouped.
 *
 * Orthogonal to [PopupMenuStyle] / [ToolCallStyle]:
 * those change chrome inside chat; this only changes the settings home
 * information architecture. A hub layout must not fork menu drawing code.
 *
 * [CLASSIC] keeps the pre-reorg hub copy and grouping (network under Models,
 * “UI Preferences” / “Interaction” wording).
 * [REORGANIZED] uses clearer names and moves network under Connections.
 *
 * Default is [REORGANIZED]; missing field on old blobs also reads as reorganized.
 */
@Serializable
enum class SettingsHubStyle {
    @SerialName("classic")
    CLASSIC,

    @SerialName("reorganized")
    REORGANIZED,
}
