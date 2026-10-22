package me.rerere.ai.provider.providers.openai

/**
 * One vocabulary for "what does this vendor's task-status word mean".
 *
 * Absorbed from the retired upstream `videogen` module (see `docs/VIDEO-GENERATION.md`), whose
 * `VideoGenerationStatus` enum made the terminal set explicit instead of leaving it implicit in the
 * `else` branch of a `when`. That distinction earns its keep here: a terminal word a vendor branch
 * does not know about used to fall through to "keep polling", so a dead job burned the whole 9–10
 * minute budget and then surfaced as a bare timeout.
 *
 * Only the **terminal half** is shared. Success handling stays vendor-specific — MiniMax's classic
 * flow needs a second request to turn a `file_id` into a download URL, and each family reads a
 * different field for the clip URL.
 *
 * Deliberately **not** in the table: DashScope's literal `UNKNOWN`. Its own loop treats that word as
 * a terminal failure (a pre-existing choice, shared with the image path); mapping it here would
 * quietly change that, and a vendor saying "I don't know" is not the same as "no".
 */
enum class VideoTaskState {
    /** Still queued or running — keep polling. */
    RUNNING,

    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,

    /** A word we have never seen; treated like [RUNNING] so a new interim state cannot break us. */
    UNKNOWN,
}

/** Maps one vendor status word onto [VideoTaskState] — case- and whitespace-insensitive. */
fun videoTaskState(status: String?): VideoTaskState = when (status?.trim()?.lowercase()) {
    null, "" -> VideoTaskState.UNKNOWN
    "success", "succeeded", "succeed", "finished", "complete", "completed" ->
        VideoTaskState.SUCCEEDED

    "fail", "failed", "error" -> VideoTaskState.FAILED
    "cancel", "canceled", "cancelled" -> VideoTaskState.CANCELLED
    "expire", "expired", "timeout", "timed out", "timed_out" -> VideoTaskState.EXPIRED

    // MiniMax classic Preparing/Queueing/Processing, H3 queued/running, Zhipu PROCESSING,
    // SiliconFlow InQueue/InProgress, and the obvious generic words.
    "queued", "queueing", "pending", "preparing", "running", "processing",
    "inqueue", "inprogress", "created", "submitted", "waiting" -> VideoTaskState.RUNNING

    else -> VideoTaskState.UNKNOWN
}

/** `true` when the job is over, one way or another. */
val VideoTaskState.isTerminal: Boolean
    get() = this != VideoTaskState.RUNNING && this != VideoTaskState.UNKNOWN

/**
 * The phrase a poll-failure message falls back to when the vendor supplied none of its own — say
 * *which* thing happened (cancelled / expired / failed) and quote the word actually seen, so an
 * unmapped status is diagnosable instead of mysterious.
 */
fun videoTaskFailurePhrase(status: String?): String = when (videoTaskState(status)) {
    VideoTaskState.CANCELLED -> "the task was cancelled (status '${status?.trim()}')"
    VideoTaskState.EXPIRED -> "the task expired (status '${status?.trim()}')"
    VideoTaskState.FAILED -> "the vendor reported a failure (status '${status?.trim()}')"
    else -> "unexpected task status '${status ?: "none reported"}'"
}
