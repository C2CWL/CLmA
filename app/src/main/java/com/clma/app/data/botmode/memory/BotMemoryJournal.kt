// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/memory-journal.ts, server/workspace.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.memory

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.common.android.DiagnosticRedactor

internal fun BotMemoryStore.recordCompletedTurn(messages: List<UIMessage>, conversationId: String) {
    val requestIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (requestIndex == -1) return
    val exchange = messages.subList(requestIndex + 1, messages.size)
    val reply = exchange.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return
    val tools = exchange.flatMap { it.getTools() }
    if (tools.any { it.isPending }) return

    val asked = excerpt(messages[requestIndex].toText(), 400).ifBlank { "Non-text request" }
    val done = excerpt(reply.toText(), 500).ifBlank {
        tools.filter { it.isExecuted }.map { it.toolName }.distinct().take(5).joinToString(", ")
            .ifBlank { "Completed turn" }
    }
    appendLog("Asked: $asked · Done: $done", conversationId)
}

private fun excerpt(text: String, maxChars: Int): String =
    BotMemoryStore.oneLine(DiagnosticRedactor.text(text)).take(maxChars)
