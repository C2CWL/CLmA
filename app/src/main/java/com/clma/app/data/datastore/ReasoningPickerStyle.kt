package com.clma.app.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How the thinking-depth picker is drawn.
 *
 * [ENERGY_BAR] is the Codex-style fat purple capsule (default).
 * [STELLAR] is the starfield / magnitude sheet.
 *
 * Settings written before this field existed also read back as [ENERGY_BAR].
 */
@Serializable
enum class ReasoningPickerStyle {
    /** Fat Codex purple energy capsule. */
    @SerialName("energy_bar")
    ENERGY_BAR,

    /** Starfield stage with magnitude icons. */
    @SerialName("stellar")
    STELLAR,
}
