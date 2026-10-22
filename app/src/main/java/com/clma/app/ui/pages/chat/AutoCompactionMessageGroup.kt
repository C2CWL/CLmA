package com.clma.app.ui.pages.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.ContextCompactionPresentation
import com.clma.app.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * A display-only grouping of assistant nodes separated by automatic context compression,
 * and of interjection sandwiches (assistant half + user interjection + assistant half).
 *
 * Generation still retains every node independently, which keeps tool replay and provider
 * history correct. The UI renders the cards and continuation text as one assistant message.
 */
internal data class AutoCompactionMessageGroup(
    val nodes: List<MessageNode>,
) {
    init {
        require(nodes.isNotEmpty())
    }

    val id: Uuid = nodes.first().id
    val terminalNode: MessageNode = nodes.last()

    val displayMessage: UIMessage = terminalNode.currentMessage.copy(
        parts = nodes.flatMap { node ->
            val message = node.currentMessage
            if (message.role == MessageRole.USER) {
                // 仅标记文本 part 以便渲染层画成内嵌卡片；附件保持原始类型，
                // 这样图片等内容仍会走各自的媒体渲染分支。
                interjectionParts(message)
            } else {
                message.parts
            }
        },
        annotations = nodes.flatMap { it.currentMessage.annotations },
        createdAt = nodes.first().currentMessage.createdAt,
    )
}

private fun interjectionParts(message: UIMessage): List<UIMessagePart> =
    message.parts.map { part ->
        if (part is UIMessagePart.Text) {
            part.copy(
                metadata = JsonObject(
                    part.metadata.orEmpty() + ("rikkahub.interjection" to JsonPrimitive(true)),
                ),
            )
        } else {
            part
        }
    }

internal fun List<MessageNode>.groupAutomaticCompactionMessages(): List<AutoCompactionMessageGroup> {
    if (isEmpty()) return emptyList()

    val groups = mutableListOf<AutoCompactionMessageGroup>()
    var index = 0
    while (index < size) {
        val groupedNodes = mutableListOf(this[index])
        while (true) {
            val last = groupedNodes.last().currentMessage
            val next = getOrNull(index + 1)?.currentMessage
            val nextNext = getOrNull(index + 2)?.currentMessage
            when {
                // 自动压缩续写：assistant(压缩工具) + assistant
                last.role == MessageRole.ASSISTANT &&
                    ContextCompactionPresentation.hasDisplayTool(last) &&
                    next?.role == MessageRole.ASSISTANT -> {
                    index++
                    groupedNodes += this[index]
                }

                // 插话三明治：assistant(未 finalize) + user + assistant。
                // 前半 finishedAt == null 是插话打断的标记（GenerationLoop 只在整轮
                // 真正结束时才设置它），据此把三段并成一组渲染为一条连续消息。
                // 停止/失败的消息会被 finalize，不会误并入。
                last.role == MessageRole.ASSISTANT &&
                    last.finishedAt == null &&
                    next?.role == MessageRole.USER &&
                    nextNext?.role == MessageRole.ASSISTANT -> {
                    groupedNodes += this[index + 1]
                    groupedNodes += this[index + 2]
                    index += 2
                }

                else -> break
            }
        }
        groups += AutoCompactionMessageGroup(groupedNodes)
        index++
    }
    return groups
}
