package com.clma.app.data.ai.transformers

import kotlinx.serialization.json.*

internal fun speechTranscript(raw: String): String? {
    val value = runCatching {
        Json.parseToJsonElement(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    }.getOrNull() as? JsonObject ?: return null
    val spoken = value["spoken"] as? JsonPrimitive
    if (spoken == null || spoken.isString || spoken.booleanOrNull != true) return null
    val text = value["transcript"] as? JsonPrimitive ?: return null
    return text.content.trim().take(4_000).takeIf { text.isString && it.any(Char::isLetterOrDigit) }
}
