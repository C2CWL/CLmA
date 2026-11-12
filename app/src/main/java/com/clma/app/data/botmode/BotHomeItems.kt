package com.clma.app.data.botmode

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.model.Conversation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * One row of the Bot home list (chat-app style): who, the latest line, when, and what needs
 * the user. Built from settings + the bot's latest top-level conversation; pure for tests.
 */
data class BotHomeRow(
    val assistantId: Uuid,
    val latestConversationId: Uuid?,
    val preview: String,
    val updatedAt: Instant?,
    val unread: Int,
    val pinned: Boolean,
    val pendingApproval: Boolean,
    val generating: Boolean,
)

/** Latest top-level (non sub-agent) conversation by update time, ignoring the pinned-first DAO order. */
fun latestTopLevelConversation(conversations: List<Conversation>): Conversation? =
    conversations
        .filter { it.parentConversationId == null }
        .maxByOrNull { it.updateAt }

/**
 * Topics shown on a bot's info page: ordinary top-level chats, plus Peer threads titled
 * `[Peer] …`. Generic sub-agent runs also set [Conversation.parentConversationId], so title
 * prefix is what keeps Peer visible without pulling those runs in.
 */
fun isBotVisibleTopic(conversation: Conversation): Boolean =
    conversation.parentConversationId == null || conversation.title.startsWith("[Peer]")

/**
 * Prefer the unread peer/thread conversation when present, else the home-row latest, else a
 * fresh id. Pure so open-path tests do not need a ViewModel.
 */
fun resolveBotOpenConversationId(
    unreadConversationId: Uuid?,
    latestConversationId: Uuid?,
    newId: () -> Uuid = { Uuid.random() },
): Uuid = unreadConversationId ?: latestConversationId ?: newId()

/**
 * One short line for the list: the last message's text with whitespace collapsed, or the
 * conversation title when the last message has no text (tool-only turns, images).
 */
fun botPreviewLine(conversation: Conversation?, maxLength: Int = 60): String {
    if (conversation == null) return ""
    val last = conversation.currentMessages.lastOrNull { msg ->
        msg.parts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
    }
    val raw = last?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString(" ") { it.text }
        .orEmpty()
        .ifBlank { conversation.title }
    val line = raw
        .replace(Regex("[`*#>_~]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (line.length > maxLength) line.take(maxLength).trimEnd() + "…" else line
}

/** True when the latest assistant turn has a tool call waiting for the user's approval. */
fun hasPendingApproval(conversation: Conversation?): Boolean {
    val messages = conversation?.currentMessages ?: return false
    val lastUser = messages.indexOfLast { it.role == MessageRole.USER }
    return messages.drop(lastUser + 1)
        .flatMap { it.parts }
        .any { it is UIMessagePart.Tool && it.isPending }
}

/** Pinned bots first (in pin order), then the rest by latest activity, newest first. */
fun sortBotHomeRows(rows: List<BotHomeRow>, pinnedOrder: List<Uuid>): List<BotHomeRow> {
    val pinIndex = pinnedOrder.withIndex().associate { (i, id) -> id to i }
    val (pinned, rest) = rows.partition { it.assistantId in pinIndex }
    return pinned.sortedBy { pinIndex[it.assistantId] } +
        rest.sortedByDescending { it.updatedAt ?: Instant.EPOCH }
}

enum class BotTimeLabelKind { TIME, YESTERDAY, WEEKDAY, DATE, NONE }

data class BotTimeLabel(val kind: BotTimeLabelKind, val text: String = "")

/**
 * Chat-app style timestamp: today → "07:05", yesterday → [BotTimeLabelKind.YESTERDAY] (the
 * UI localises it), within a week → weekday, older → "M/d" (or "yyyy/M/d" for another year).
 */
fun botTimeLabel(
    at: Instant?,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    weekdayName: (java.time.DayOfWeek) -> String = { it.name },
): BotTimeLabel {
    if (at == null) return BotTimeLabel(BotTimeLabelKind.NONE)
    val day = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when {
        day == today || day.isAfter(today) ->
            BotTimeLabel(BotTimeLabelKind.TIME, at.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")))
        day == today.minusDays(1) -> BotTimeLabel(BotTimeLabelKind.YESTERDAY)
        day.isAfter(today.minusDays(7)) -> BotTimeLabel(BotTimeLabelKind.WEEKDAY, weekdayName(day.dayOfWeek))
        day.year == today.year -> BotTimeLabel(BotTimeLabelKind.DATE, "${day.monthValue}/${day.dayOfMonth}")
        else -> BotTimeLabel(BotTimeLabelKind.DATE, "${day.year}/${day.monthValue}/${day.dayOfMonth}")
    }
}

