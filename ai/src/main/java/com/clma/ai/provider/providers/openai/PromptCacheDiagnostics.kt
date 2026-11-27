package com.clma.ai.provider.providers.openai

import com.clma.ai.core.TokenUsage
import com.clma.ai.util.json
import com.clma.common.android.Logging
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToStream

/** Session-local opt-in. No raw request content or identifiers go to the diagnostic log. */
object PromptCacheDiagnostics {
    private const val TAG = "PromptCachePrefix"
    @Volatile private var tracker: PromptCachePrefixTracker? = null

    fun isEnabled(): Boolean = tracker != null

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled()) return
        tracker = if (enabled) PromptCachePrefixTracker() else null
    }

    internal fun begin(
        sessionId: String?,
        providerId: String,
        endpoint: String,
        body: JsonObject,
        streaming: Boolean,
    ): RequestTrace? {
        val active = tracker ?: return null
        if (sessionId == null) return null // No reliable conversation to compare against.
        val sample = active.record(sessionId, providerId, endpoint, body)
        if (tracker !== active) return null
        Logging.log(TAG, "request=${sample.id} series=${sample.series} stream=$streaming ${sample.fields}")
        return RequestTrace { usage ->
            if (tracker === active) Logging.log(TAG, "request=${sample.id} ${formatCacheUsage(usage)}")
        }
    }

    internal class RequestTrace(val reportUsage: (TokenUsage?) -> Unit)
}

// These labels avoid the log redactor’s credential-field pattern (e.g. *token*=…).
internal fun formatCacheUsage(usage: TokenUsage?): String = if (usage == null) {
    "usage=unreported"
} else {
    "input=${usage.promptTokens} " +
        "cacheHit=${usage.cachedTokens.takeIf { usage.cachedTokensReported } ?: "unreported"} " +
        "cacheMiss=${usage.cacheMissTokens ?: "unreported"}"
}

internal class PromptCachePrefixTracker {
    private companion object {
        const val MAX_SERIES = 24
        const val MAX_MESSAGES = 1024
        // Not all gateways fold these fields into the model prompt, so compare them separately.
        val OPTIONS = setOf("prompt_cache_key", "prompt_cache_retention", "provider", "response_format",
            "tool_choice", "parallel_tool_calls", "functions", "function_call")
    }

    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "HmacSHA256")
    // Access order keeps active conversations available when older entries are evicted.
    private val previous = LinkedHashMap<Scope, Entry>(MAX_SERIES, 0.75f, true)
    private var serial = 0L

    internal data class Sample(val id: String, val series: String, val fields: String)
    private data class Scope(val session: String, val provider: String, val endpoint: String, val model: Fingerprint?)
    private data class Fingerprint(val hash: String, val bytes: Long)
    private data class Snapshot(
        val messages: List<Fingerprint>,
        val messageCount: Int?,
        val tools: Fingerprint?,
        val options: Fingerprint,
    )
    private data class Entry(val id: String, val series: String, val snapshot: Snapshot)

    fun record(session: String, provider: String, endpoint: String, body: JsonObject): Sample {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        val scope = Scope(session, provider, endpoint, body["model"]?.let { fingerprint(it, mac) })
        val messages = body["messages"] as? JsonArray
        val current = Snapshot(
            messages = messages.orEmpty().asSequence().take(MAX_MESSAGES).map { fingerprint(it, mac) }.toList(),
            messageCount = messages?.size,
            tools = body["tools"]?.let { fingerprint(it, mac) },
            options = fingerprint(JsonObject(body.filterKeys { it in OPTIONS }), mac),
        )
        return compareAndStore(scope, current)
    }

    @Synchronized
    private fun compareAndStore(scope: Scope, current: Snapshot): Sample {
        val prior = previous[scope]
        // Random prefix prevents unrelated sessions in retained logs from sharing request IDs.
        val id = "$runId-${++serial}"
        val series = prior?.series ?: id
        previous[scope] = Entry(id, series, current)
        if (previous.size > MAX_SERIES) previous.entries.iterator().apply { next(); remove() }
        val old = prior?.snapshot
        val toolsChanged = old?.let { it.tools != current.tools }
        val optionsChanged = old?.let { it.options != current.options }
        val comparison = if (old?.messageCount == null || current.messageCount == null) {
            "prefixMessages=unavailable firstChangedMessage=unavailable"
        } else {
            val limit = minOf(old.messages.size, current.messages.size)
            var common = 0
            while (common < limit && old.messages[common] == current.messages[common]) common++
            val prefixBytes = current.messages.take(common).sumOf { it.bytes }
            val firstChanged = when {
                common < limit -> "$common"
                common == minOf(old.messageCount, current.messageCount) ->
                    if (old.messageCount == current.messageCount) "none" else "$common"
                else -> "beyond_limit"
            }
            val changeKind = when {
                common < limit -> "rewrite"
                firstChanged == "beyond_limit" -> "unknown"
                old.messageCount < current.messageCount -> "append"
                old.messageCount > current.messageCount -> "truncate"
                else -> "identical"
            }
            "prefixMessages=$common prefixMessageBytes=$prefixBytes firstChangedMessage=$firstChanged " +
                "change=$changeKind oldMessageHash=${old.messages.getOrNull(common)?.hash ?: "-"} " +
                "newMessageHash=${current.messages.getOrNull(common)?.hash ?: "-"}"
        }
        return Sample(id, series,
            "previous=${prior?.id ?: "none"} messages=${current.messageCount ?: "unavailable"} " +
                "toolsChanged=${toolsChanged ?: "unknown"} optionsChanged=${optionsChanged ?: "unknown"} $comparison")
    }

    private val runId = Random.nextLong().toULong().toString(16)

    @OptIn(ExperimentalSerializationApi::class)
    private fun fingerprint(element: JsonElement, mac: Mac): Fingerprint {
        var bytes = 0L
        val sink = object : OutputStream() {
            override fun write(b: Int) {
                mac.update(b.toByte())
                bytes++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                mac.update(b, off, len)
                bytes += len
            }
        }
        // Encode with the same JSON format as the HTTP body, without copying large attachments.
        json.encodeToStream(JsonElement.serializer(), element, sink)
        return Fingerprint(mac.doFinal().toHexString(), bytes)
    }
}
