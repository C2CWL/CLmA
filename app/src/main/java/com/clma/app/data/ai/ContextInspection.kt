package com.clma.app.data.ai

import com.clma.ai.core.InputSchema
import com.clma.ai.core.MessageRole
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.common.android.DiagnosticRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** App-side request after input transformers, not provider wire JSON or a tokenizer result. */
data class ContextInspection(
    val sections: List<ContextSection>,
    val capturedAt: Long = System.currentTimeMillis(),
    val tokenCount: Long? = null,
) {
    val utf8Bytes: Long get() = sections.filter { it.kind != ContextSectionKind.MEMORY_SOURCE }.sumOf { it.utf8Bytes }

    fun withoutPreviews(): ContextInspection = copy(sections = sections.map { it.copy(preview = null) })

    companion object {
        private const val PREVIEW_CHARS = 12_000

        fun capture(
            messages: List<UIMessage>,
            tools: List<Tool>,
            memoryPrompt: String,
            includePreviews: Boolean = true,
        ): ContextInspection {
            fun section(kind: ContextSectionKind, content: String): ContextSection = ContextSection(
                kind = kind,
                utf8Bytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                chars = content.length.toLong(),
                preview = if (includePreviews) DiagnosticRedactor.body(content).take(PREVIEW_CHARS) else null,
                truncated = content.length > PREVIEW_CHARS,
            )
            fun encoded(items: List<UIMessage>): String = JsonArray(items.map { message ->
                buildJsonObject {
                    put("role", message.role.name)
                    put("parts", JsonArray(message.parts.map {
                        Json.encodeToJsonElement(UIMessagePart.serializer(), it)
                    }))
                }
            }).toString()
            val system = messages.filter { it.role == MessageRole.SYSTEM }
            val synthetic = messages.filter { it.role != MessageRole.SYSTEM && it.isSynthetic }
            val history = messages.filter { it.role != MessageRole.SYSTEM && !it.isSynthetic }
            val toolJson = JsonArray(tools.map { tool ->
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    val schema = tool.parameters()
                    put("parameters", schema?.let { Json.encodeToJsonElement(InputSchema.serializer(), it) }
                        ?: JsonObject(emptyMap()))
                }
            }).toString()
            return ContextInspection(listOf(
                section(ContextSectionKind.SYSTEM, encoded(system)),
                section(ContextSectionKind.MEMORY_SOURCE, JsonPrimitive(memoryPrompt).toString()),
                section(ContextSectionKind.SYNTHETIC, encoded(synthetic)),
                section(ContextSectionKind.HISTORY, encoded(history)),
                section(ContextSectionKind.TOOLS, toolJson),
            ))
        }
    }
}

enum class ContextSectionKind { SYSTEM, MEMORY_SOURCE, SYNTHETIC, HISTORY, TOOLS }

data class ContextSection(
    val kind: ContextSectionKind,
    val utf8Bytes: Long,
    val chars: Long,
    val preview: String?,
    val truncated: Boolean,
)
