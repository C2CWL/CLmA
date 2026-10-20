package me.rerere.rikkahub.service

import java.util.concurrent.ConcurrentHashMap
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * 生成中插话的暂存队列（per-conversation）。
 *
 * 与 [me.rerere.rikkahub.data.ai.tools.ConversationInjections] 的区别：那里的通知是
 * request-only 的（不渲染、不持久化），而插话是**真实的用户消息**——GenerationLoop
 * 在下一次模型请求前把它并入 messages 并 emit，于是它既进入请求上下文，也作为普通
 * 用户气泡落盘。
 *
 * 为什么需要队列而不是直接写 conversation：生成期间 ChatService 会用
 * `updateCurrentMessages(chunk.messages)` 整体对齐消息列表，外部写入会被下一次
 * chunk 回写覆盖。由 GenerationLoop 自己把消息并入 messages 再 emit，顺序才是确定的。
 *
 * 生命周期：进程内有效。未被消费的条目在本轮结束时由 ChatService 转成普通排队消息，
 * 不会丢。
 */
object ConversationSteers {

    /** 单会话最多暂存的插话数；超出丢最旧，避免放弃的会话无限累积。 */
    private const val MAX_PENDING = 8

    private val byConv = ConcurrentHashMap<Uuid, ArrayDeque<UIMessage>>()

    /** 暂存一条待并入当前回合的用户消息。 */
    fun push(conversationId: Uuid, message: UIMessage) {
        val deque = byConv.computeIfAbsent(conversationId) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(message)
            while (deque.size > MAX_PENDING) deque.removeFirst()
        }
    }

    /** 当前有多少条待消费的插话（0 表示没有）。 */
    fun pendingCount(conversationId: Uuid): Int {
        val deque = byConv[conversationId] ?: return 0
        return synchronized(deque) { deque.size }
    }

    /** 原子取走全部待消费插话（按入队顺序），并清空该会话的队列。 */
    fun drain(conversationId: Uuid): List<UIMessage> {
        val deque = byConv[conversationId] ?: return emptyList()
        synchronized(deque) {
            if (deque.isEmpty()) return emptyList()
            val out = deque.toList()
            deque.clear()
            // 不 remove key：与并发 push 的 computeIfAbsent 竞态时，删掉 key 会让新消息进入
            // 一个已被移除的 deque 而永久丢失。空 deque 由 clear() 回收。
            return out
        }
    }

    /** 丢弃该会话所有未消费的插话（用户主动停止生成时调用）。 */
    fun clear(conversationId: Uuid) {
        byConv.remove(conversationId)
    }
}
