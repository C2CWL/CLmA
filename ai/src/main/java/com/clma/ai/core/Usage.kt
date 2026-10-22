package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
    // Provider-reported generation cost in USD (OpenRouter `usage.cost`). Null when the
    // provider doesn't report it. Nullable + defaulted so older persisted messages decode fine.
    val cost: Double? = null,
    // --- P2-11a: provenance, so that a missing report is never read as a real zero ---------
    // `cachedTokens == 0` is ambiguous: it means either "the provider reported a cache miss"
    // or "this provider does not report cache fields at all". Averaging both groups together
    // is what made the cache hit rate untrustworthy. Producers set this flag to true only
    // when they actually saw a cache field in the payload.
    val cachedTokensReported: Boolean = false,
    // Provider-reported cache-miss tokens (DeepSeek `prompt_cache_miss_tokens`). Null when
    // unreported. Note that `promptTokens - cachedTokens` is NOT a safe substitute: some
    // providers exclude cached tokens from promptTokens and others include them.
    val cacheMissTokens: Int? = null,
    // Provider-reported reasoning/thinking tokens, when the provider exposes them.
    val reasoningTokens: Int? = null,
    // Provider-reported cache-write tokens (Anthropic `cache_creation_input_tokens`). Writes
    // are billed at their own rate, so they must not be folded into hits or misses.
    val cacheWriteTokens: Int? = null,
)

fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    // P2-11a: keep the provider-reported total instead of recomputing it from the two halves.
    // Some providers count tokens that prompt+completion does not cover (reasoning, cache
    // read/write), so recomputing silently diverges from the value the provider billed.
    // Verified no-op for every recorded stream trace: all of them satisfy total == p + c.
    val reportedTotal = if (other.totalTokens > 0) {
        other.totalTokens
    } else {
        this?.totalTokens ?: 0
    }
    val totalTokens = if (reportedTotal > 0) reportedTotal else promptTokens + completionTokens
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    val cost = other.cost ?: this?.cost
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens,
        cost = cost,
        // `cachedTokens` deliberately keeps its historical merge rule (overwrite when the
        // incoming chunk is non-zero). The provenance fields use last-non-null-wins.
        cachedTokensReported = (this?.cachedTokensReported ?: false) || other.cachedTokensReported,
        cacheMissTokens = other.cacheMissTokens ?: this?.cacheMissTokens,
        reasoningTokens = other.reasoningTokens ?: this?.reasoningTokens,
        cacheWriteTokens = other.cacheWriteTokens ?: this?.cacheWriteTokens,
    )
}
