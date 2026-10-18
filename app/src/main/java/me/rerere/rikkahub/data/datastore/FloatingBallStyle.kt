package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * How the floating ball is drawn. [ORIGINAL] (default) is the round halo ball exactly as before;
 * [CAPSULE] is the optional status capsule: avatar, assistant name, status and turn timer.
 */
@Serializable
enum class FloatingBallStyle(val key: String) {
    ORIGINAL("original"),
    CAPSULE("capsule");

    companion object {
        /** Unknown / missing / blank values fall back to [ORIGINAL], so old stores keep the ball. */
        fun fromKey(raw: String?): FloatingBallStyle =
            entries.firstOrNull { it.key == raw } ?: ORIGINAL
    }
}
