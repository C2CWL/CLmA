// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/thread-title-generate.ts, server/routes/suggested-reply.ts, src/lib/unread-context.ts
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE).
// Modified: ported to Kotlin and changed for CLmA.

package com.clma.app.data.botmode

import com.clma.ai.core.MessageRole
import com.clma.ai.provider.ModelType
import com.clma.ai.provider.ProviderSetting
import com.clma.app.data.datastore.Settings
import com.clma.llamacpp.LlamaCppCatalog
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart

internal fun Settings.botChatLocalModels() = providers.filterIsInstance<ProviderSetting.LlamaCppLocal>()
    .filter { it.enabled }
    .flatMap { provider ->
        provider.models.filter { model ->
            model.type == ModelType.CHAT && LlamaCppCatalog.ENTRIES.none {
                it.file == model.modelId && it.projectorFile != null
            }
        }.map { provider to it }
    }

internal fun List<UIMessage>.latestReplyBoundary(): UIMessage? {
    val ask = indexOfLast { it.role == MessageRole.USER }
    return drop(ask + 1).firstOrNull { it.role == MessageRole.ASSISTANT }
}

internal fun List<UIMessage>.questionBefore(replyIndex: Int): UIMessage? =
    take(replyIndex.coerceAtLeast(0)).lastOrNull { it.role == MessageRole.USER }

internal fun UIMessage.botChatText(): String = parts.filterIsInstance<UIMessagePart.Text>()
    .joinToString("\n") { it.text }.trim()

internal fun botReplyPrompt(ask: String, reply: String): String = """
    Suggest the next message the person might send in this chat with their assistant.
    Write one short line in the person's voice and language, at most 12 words. Use plain text.
    If no natural next message fits, answer NONE.

    <person>${ask.takeLast(600)}</person>
    <assistant>${reply.takeLast(1400)}</assistant>
""".trimIndent()

internal fun botTitlePrompt(messages: List<UIMessage>): String? {
    val lines = messages.asReversed().asSequence()
        .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        .mapNotNull { message ->
            message.botChatText().takeIf { it.isNotBlank() }?.let {
                "${message.role}: ${it.take(400)}"
            }
        }.take(8).toList().asReversed()
    if (lines.isEmpty()) return null
    return "Write a short title for this conversation in its primary language. " +
        "Return only the title, with no quotes or markup.\n\n" + lines.joinToString("\n")
}
