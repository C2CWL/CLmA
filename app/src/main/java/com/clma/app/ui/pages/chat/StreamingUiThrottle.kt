package com.clma.app.ui.pages.chat

import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.model.Conversation
import kotlinx.coroutines.flow.Flow

internal fun isAppendOnlyStreamingUpdate(old: Conversation, new: Conversation): Boolean {
    if (old.copy(messageNodes = new.messageNodes, updateAt = new.updateAt) != new) return false
    if (old.messageNodes.size != new.messageNodes.size) return false
    return old.messageNodes.indices.all { i ->
        val a = old.messageNodes[i]
        val b = new.messageNodes[i]
        a.copy(messages = b.messages) == b && a.messages.size == b.messages.size &&
            a.messages.indices.all { j ->
                val before = a.messages[j]
                val after = b.messages[j]
                before == after || (before.finishedAt == null &&
                    before.copy(parts = after.parts) == after &&
                    before.parts.size == after.parts.size && before.parts.indices.all { k ->
                        val x = before.parts[k]
                        val y = after.parts[k]
                        when {
                            x == y -> true
                            x is UIMessagePart.Text && y is UIMessagePart.Text ->
                                y.text.startsWith(x.text) && x.copy(text = y.text) == y
                            x is UIMessagePart.Reasoning && y is UIMessagePart.Reasoning ->
                                y.reasoning.startsWith(x.reasoning) && x.copy(reasoning = y.reasoning) == y
                            x is UIMessagePart.Tool && y is UIMessagePart.Tool ->
                                y.input.startsWith(x.input) && x.copy(input = y.input) == y
                            x is UIMessagePart.Image && y is UIMessagePart.Image ->
                                y.url.startsWith(x.url) && x.copy(url = y.url) == y
                            else -> false
                        }
                    })
            }
    }
}

internal fun Flow<Conversation>.throttleAppendOnlyStreaming(windowMs: Long = 80): Flow<Conversation> =
    throttleStreamingUpdates(windowMs, ::isAppendOnlyStreamingUpdate)
