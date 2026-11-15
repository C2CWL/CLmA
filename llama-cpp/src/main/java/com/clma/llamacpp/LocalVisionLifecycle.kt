package com.clma.llamacpp

import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart

/** Visual catalog models are request-scoped, never a text-only resident model. */
internal object LocalVisionLifecycle {
    fun requiresMedia(modelId: String): Boolean =
        LlamaCppCatalog.ENTRIES.any { it.file == modelId && it.projectorFile != null }

    fun validateRequest(modelId: String, messages: List<UIMessage>) {
        if (!requiresMedia(modelId)) return
        fun hasMedia(parts: List<UIMessagePart>): Boolean = parts.any {
            it is UIMessagePart.Image || it is UIMessagePart.Video ||
                (it is UIMessagePart.Tool && it.isExecuted && hasMedia(it.output))
        }
        require(messages.any { hasMedia(it.parts) }) {
            "This visual model loads only for image/video requests; choose a text model for plain chat"
        }
    }
}
