package com.clma.app.data.ai

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart

/**
 * Images in this many of the most recent user turns (the one being sent plus the three before
 * it) are sent to the model; older user images are replaced by [ELIDED_USER_IMAGE_TEXT].
 */
const val USER_IMAGE_KEEP_LAST_TURNS = 4

/** What the model sees instead of an aged-out user image. */
const val ELIDED_USER_IMAGE_TEXT = "【这里原来有一张图片，为节省内存已省略】"

/**
 * Request-only: replaces images in user messages older than the last [keepLastTurns] user
 * turns with a short placeholder. Every image in the history used to be re-encoded to base64 on
 * every request, tool step and retry, so a long chat with photos built a request of tens of MB
 * (the "Failed to allocate 88 MB" OOM). The stored conversation and the UI are untouched; this
 * is applied to the request copy only, like [ageOldToolImages] for tool screenshots.
 *
 * Synthetic user messages (continuation instructions, injected notices) do not count as turns.
 */
fun List<UIMessage>.ageOldUserImages(keepLastTurns: Int = USER_IMAGE_KEEP_LAST_TURNS): List<UIMessage> {
    var userTurnsSeen = 0
    return asReversed().map { message ->
        if (message.role != MessageRole.USER || message.isSynthetic) return@map message
        userTurnsSeen++
        if (userTurnsSeen <= keepLastTurns) return@map message
        if (message.parts.none { it is UIMessagePart.Image }) return@map message
        message.copy(
            parts = message.parts.map { part ->
                if (part is UIMessagePart.Image) UIMessagePart.Text(ELIDED_USER_IMAGE_TEXT) else part
            },
        )
    }.asReversed()
}
