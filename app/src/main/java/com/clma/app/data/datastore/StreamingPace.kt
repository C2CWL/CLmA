package com.clma.app.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How fast streamed reply text reaches the chat list.
 *
 * Only pure appends are batched; a structural change (a new message, a tool call changing state)
 * is always published immediately, at every pace.
 */
@Serializable
enum class StreamingPace {
    /** Every token is published as it arrives. Costs the most to draw. */
    @SerialName("immediate")
    IMMEDIATE,

    /** Appends are merged for about four frames at 120 Hz. */
    @SerialName("balanced")
    BALANCED,

    /** Appends are merged for about six frames at 120 Hz. The cheapest of the three. */
    @SerialName("saver")
    SAVER,
}

/** Milliseconds of append-only batching for [pace]. Zero means no batching at all. */
fun streamingPaceWindowMs(pace: StreamingPace): Long = when (pace) {
    StreamingPace.IMMEDIATE -> 0L
    StreamingPace.BALANCED -> 32L
    StreamingPace.SAVER -> 50L
}
