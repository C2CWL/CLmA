package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage

/**
 * What the visible messages should be when the stream is re-issued after a failure that came
 * before any meaningful output.
 *
 * StreamChunkHandler materializes a part on the bare Start markers (`TextStart` -> `Text("")`,
 * `ReasoningStart`, `ToolCallStart` -> a Tool with empty input), but those markers don't count
 * as meaningful output, so the retry policy happily re-runs the request. Without a rollback the
 * failed attempt's empty parts stay in the assistant message and the retried attempt appends
 * after them - a blank text part wedged between tool calls, which splits one step group into
 * several. A retry only fires when nothing meaningful arrived, so going back to the snapshot
 * taken at the start of the attempt only drops those empty shells. The meaningful-output guard
 * is kept anyway so a future caller can't silently throw away real output.
 */
internal fun messagesForStreamRetry(
    current: List<UIMessage>,
    attemptStart: List<UIMessage>,
    receivedMeaningfulOutput: Boolean,
): List<UIMessage> = if (receivedMeaningfulOutput) current else attemptStart
