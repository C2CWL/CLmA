package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.ContextBudgetPlanner
import me.rerere.rikkahub.data.datastore.getEffectiveContextLength

/**
 * 上下文预算提醒注入转换器
 *
 * 当本次请求的上下文估算值达到助手配置的提醒阈值时, 在最后一条用户消息之前注入一条合成的
 * `<context_reminder>`, 让模型知道上下文快满了, 从而自行判断是否要调用 `compact_context`
 * 主动压缩。
 *
 * 注入方式参照 [TimeReminderTransformer](合成 user 消息)。只在助手启用了 `compact_context`
 * 工具 (`Assistant.enableCompactContextTool`) 时生效 —— 没有工具可调时提醒没有意义。
 *
 * 阈值是**每助手**的 `Assistant.contextBudgetReminderPercent`(占**有效模型窗口**的百分比, 默认
 * 70%)。它刻意**低于**自动压缩的默认阈值(80%): 提醒必须先于客户端的强制压缩到达, 模型才有机会
 * 自己决定是否需要压缩; 否则同一个阈值下客户端会抢先压缩, 提醒沦为多余动作。
 *
 * 窗口取自 `Settings.getEffectiveContextLength`(与自动压缩、压缩输入预算同一个解析器), 因为它才是
 * 「模型窗口」的唯一事实来源: 服务商公布的元数据(OpenRouter `context_length` / Gemini
 * `inputTokenLimit`)、用户显式声明的 token 上限、静态 `ModelRegistry` 条目, 依次兜底。窗口仍然
 * 完全未知时才不注入 —— 猜一个窗口会让提醒在错误的时机出现。
 */
object ContextBudgetReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableCompactContextTool) return messages
        // The window comes from the same resolver the automatic compactor uses, so the reminder
        // always fires before the client-side compaction that is supposed to be its fallback.
        val contextLength = ctx.settings.getEffectiveContextLength(ctx.model) ?: return messages
        val reminder = buildContextBudgetReminder(
            messages = messages,
            contextLength = contextLength,
            reminderPercent = ctx.assistant.contextBudgetReminderPercent,
        ) ?: return messages
        return injectContextReminder(messages, reminder)
    }
}

/**
 * 纯函数: 估算 [messages] 的上下文占用, 达到阈值时返回提醒文本, 否则返回 null。
 * 抽离出来以便单元测试, 不依赖 Android / Compose。
 */
internal fun buildContextBudgetReminder(
    messages: List<UIMessage>,
    contextLength: Int,
    reminderPercent: Int,
): String? {
    val plan = ContextBudgetPlanner.plan(
        messages = messages,
        contextLength = contextLength,
        thresholdPercent = reminderPercent,
    )
    if (!plan.shouldCompact) return null
    val percent = (plan.estimatedInputTokens.toLong() * 100L / contextLength).coerceIn(0L, 100L)
    return buildString {
        append("<context_reminder>")
        append("Context budget: ~")
        append(plan.estimatedInputTokens)
        append(" / ")
        append(contextLength)
        append(" tokens (")
        append(percent)
        append("%) used, at or above the configured reminder threshold (~")
        append(plan.triggerTokens)
        append(" tokens). ")
        append("If you no longer need the exact wording of earlier turns, consider calling ")
        append("compact_context to shrink the context; otherwise continue, but keep replies concise.")
        append("</context_reminder>")
    }
}

/**
 * 纯函数: 在最后一条用户消息之前插入一条合成的提醒消息(与 [TimeReminderTransformer] 的注入方式一致)。
 * 没有任何用户消息时追加到末尾。
 */
internal fun injectContextReminder(messages: List<UIMessage>, reminder: String): List<UIMessage> {
    val reminderMessage = UIMessage.user(reminder).copy(isSynthetic = true)
    val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (lastUserIndex < 0) return messages + reminderMessage
    return messages.toMutableList().apply { add(lastUserIndex, reminderMessage) }
}
