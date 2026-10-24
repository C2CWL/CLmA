package com.clma.app.data.ai

import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.limitContext

/**
 * [limitContext] 的包装: 压缩摘要永远不能被消息条数上限裁掉。
 *
 * 压缩后的请求视图是 `[summary] + rawTail`, summary 是一条合成(合成 = 由程序生成、
 * 非用户输入)消息, 代表尾段之前的所有历史, 也是那段历史仅存的记录。而 [limitContext]
 * 是纯位置驱动的: 条数超限时丢掉开头的一段, 这条 summary 恰好就在被丢的那一段里。
 * 两者同时开启时, 请求会变成一个没有历史的空壳 —— 比不压缩更糟, 因为用户以为模型
 * 仍然记得前面的对话。
 *
 * 两个开关本身互不相关(一个限制条数, 一个限制总量), 且默认都关闭, 所以这个组合长期
 * 没有被发现; 恰好同时打开这两个开关的用户会丢掉全部上下文。
 *
 * 约定:
 * - 开头的合成消息(压缩摘要等)固定保留, 不参与条数计算; 尾部按 `limit - 固定条数`
 *   单独限流(至少保留 1 条, 使 limit 为 1 时仍然有内容可回答)。
 * - 开头没有合成消息时, 行为与 [limitContext] 完全一致。
 * - limit 关闭或不超限时原样返回。
 */
internal fun List<UIMessage>.limitContextPreservingLeadingSynthetic(limit: Int): List<UIMessage> {
    if (limit <= 0 || size <= limit) return this

    val pinned = indexOfFirst { !it.isSynthetic }.let { if (it < 0) size else it }
    // 常见情况: 对话以一条真实的用户消息开头。
    if (pinned == 0) return limitContext(limit)
    // 整段都是合成消息(仅请求期存在的脚手架), 全丢掉就没有可回答的内容了, 原样返回。
    if (pinned >= size) return this

    val tailLimit = (limit - pinned).coerceAtLeast(1)
    return subList(0, pinned) + subList(pinned, size).limitContext(tailLimit)
}