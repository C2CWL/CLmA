package com.clma.app.ui.pages.chat

import com.clma.ai.core.MessageRole

/** One row of the conversation outline: a chat list item plus the turn it belongs to. */
internal data class ChatOutlineEntry(
    /** Chat list item index, i.e. the display-group index the outline scrolls to. */
    val itemIndex: Int,
    /** 1-based turn number; every user message opens a new turn. */
    val turn: Int,
    /** True when this entry is the user message that opened [turn]. */
    val isTurnStart: Boolean,
    val label: String,
)

/** Minimal input for [buildChatOutline]: what the outline needs from a display group. */
internal data class ChatOutlineItem(
    val role: MessageRole,
    val text: String,
)

/**
 * Groups the visible chat items into whole turns: a user message starts a turn, and every
 * assistant / tool item after it belongs to that turn until the next user message. A
 * conversation that starts with an assistant (or only has system/tool items) still gets turn 1.
 *
 * Pure so the grouping can be unit-tested; [label] keeps the existing "role: first 120 chars"
 * format the outline already used.
 */
internal fun buildChatOutline(
    items: List<ChatOutlineItem>,
    maxLabelChars: Int = 120,
): List<ChatOutlineEntry> {
    var turn = 0
    return items.mapIndexed { index, item ->
        val isTurnStart = item.role == MessageRole.USER || turn == 0
        if (isTurnStart) turn += 1
        ChatOutlineEntry(
            itemIndex = index,
            turn = turn,
            isTurnStart = isTurnStart,
            label = "${item.role}: ${item.text.trim().take(maxLabelChars).ifBlank { "[…]" }}",
        )
    }
}
