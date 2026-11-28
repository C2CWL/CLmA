package com.clma.app.data.ai.transformers

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.app.utils.toLocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.toJavaInstant

/**
 * 时间提醒注入转换器
 *
 * 在请求末尾追加 <time_reminder>，避免改写历史消息前缀。时间提醒仍然用于帮助模型理解
 * 对话时间间隔，但其字节变化只影响尾部，不会打断 prompt cache 的稳定前缀。
 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableTimeReminder) return messages
        val zoneId = resolveZoneId(ctx.settings.customTimezone)
        return applyTimeReminder(messages, ctx.assistant.timeReminderIntervalMinutes, zoneId)
    }

    private fun resolveZoneId(customTz: String?): ZoneId =
        customTz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
}

internal fun applyTimeReminder(
    messages: List<UIMessage>,
    intervalMinutes: Int = 60,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<UIMessage> {
    val thresholdSeconds = intervalMinutes.coerceAtLeast(1).toLong() * 60
    val reminders = mutableListOf<UIMessage>()
    val tz = runCatching { TimeZone.of(zoneId.id) }.getOrElse { TimeZone.currentSystemDefault() }

    var firstUserFound = false
    for (i in messages.indices) {
        val current = messages[i]
        if (current.role == MessageRole.USER) {
            val currInstant = current.createdAt.toInstant(tz)
            if (!firstUserFound) {
                firstUserFound = true
                reminders.add(buildTimeReminderMessage(null, currInstant, zoneId))
            } else {
                val previous = messages[i - 1]
                val prevInstant = previous.createdAt.toInstant(tz)
                val gapSeconds = (currInstant - prevInstant).inWholeSeconds
                if (gapSeconds > thresholdSeconds) {
                    reminders.add(buildTimeReminderMessage(gapSeconds, currInstant, zoneId))
                }
            }
        }
    }

    return messages + reminders
}

private fun buildTimeReminderMessage(gapSeconds: Long?, instant: Instant, zoneId: ZoneId): UIMessage {
    val javaInstant = instant.toJavaInstant()
    val dayOfWeek = javaInstant.atZone(zoneId).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
    val timeStr = javaInstant.toLocalDateTime(zoneId)
    val content = if (gapSeconds != null) {
        val gapText = formatGap(gapSeconds)
        "<time_reminder>Current time: $dayOfWeek, $timeStr ($gapText since last message)</time_reminder>"
    } else {
        "<time_reminder>Current time: $dayOfWeek, $timeStr</time_reminder>"
    }
    return UIMessage.user(content).copy(isSynthetic = true)
}

private fun formatGap(seconds: Long): String {
    return when {
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86400 -> "${seconds / 3600} h"
        else -> "${seconds / 86400} d"
    }
}
