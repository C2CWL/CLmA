package com.clma.common.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Applied before diagnostics enter memory, disk or an export; never changes the live request. */
object DiagnosticRedactor {
    private const val MASK = "[REDACTED]"
    private val safeHeaders = setOf("content-type", "content-length", "accept", "date", "retry-after")
    private val sensitiveKey = Regex(
        "(?i).*(authorization|cookie|api.?key|token|secret|password|passwd|credential|private.?key|" +
            "email|phone|address|session).*",
    )
    private val credentials = Regex("(?i)\\b(Bearer|Basic)\\s+[^\\s,;\"']+")
    private val apiKey = Regex("\\bsk-[A-Za-z0-9_-]{8,}")
    private val assignment = Regex(
        """(?i)(\b(?:[\w-]{0,64}(?:api[_-]?key|token|secret|password|passwd|authorization|cookie|email|phone|private[_-]?key)[\w-]{0,64})["']?\s*[=:]\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s&,;]+)""",
    )
    private val urls = Regex("https?://[^\\s<>\"']+")

    fun headers(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) ->
        if (name.lowercase() in safeHeaders) text(value) else MASK
    }

    /** Query values, user info and fragments may contain arbitrary credentials, not only named keys. */
    fun url(value: String): String {
        val parsed = value.toHttpUrlOrNull() ?: return "[URL omitted]"
        return parsed.newBuilder().username("").password("").query(null).fragment(null).apply {
            parsed.queryParameterNames.forEach { addQueryParameter(it, MASK) }
        }.build().toString()
    }

    fun text(value: String): String = value
        .replace(urls) { url(it.value) }
        .replace(credentials) { "${it.groupValues[1]} $MASK" }
        .replace(apiKey, MASK)
        .replace(assignment) { "${it.groupValues[1]}$MASK" }

    /** Fail closed on non-JSON/malformed bodies; regex replacement alone cannot safely parse them. */
    fun body(value: String): String {
        if (value.startsWith("<omitted ") && value.endsWith(" bytes>")) return value
        val element = runCatching { Json.parseToJsonElement(value) }.getOrNull()
        // Partial or malformed bodies are dropped. A bare string or assignment is redacted as text.
        if (element == null) return "[Non-JSON body omitted]"
        if (element !is JsonObject && element !is JsonArray) return text(value)
        return json(element).toString()
    }

    fun json(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, item) ->
            if (sensitiveKey.matches(key)) JsonPrimitive(MASK) else json(item)
        })
        is JsonArray -> JsonArray(value.map(::json))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(text(value.content)) else value
    }
}
