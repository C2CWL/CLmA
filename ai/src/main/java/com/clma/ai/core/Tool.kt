package com.clma.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.clma.ai.provider.Model
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> List<UIMessagePart>,
    /**
     * Optional bounded progress while [execute] is still running. The returned text is UI-only:
     * it is not tool output, is not sent back to the model, and must not mark the call complete.
     * Null means the tool has no live snapshot.
     */
    val progress: (suspend () -> String?)? = null,
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
