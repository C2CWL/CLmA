package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * How the round floating ball tucks itself into the screen edge when idle. [HANDLE] (default) is
 * the slim edge handle, exactly as before; [HALF_BALL] slides the whole ball half off-screen and
 * dims it, the look from before the slim handle. The capsule style always uses its handle.
 */
@Serializable
enum class FloatingBallTuckStyle(val key: String) {
    HANDLE("handle"),
    HALF_BALL("half_ball");

    companion object {
        /** Unknown / missing / blank values fall back to [HANDLE], so old stores keep the handle. */
        fun fromKey(raw: String?): FloatingBallTuckStyle =
            entries.firstOrNull { it.key == raw } ?: HANDLE
    }
}
