package me.rerere.rikkahub.data.ai

/**
 * The single place where the "model window" — the number every context-budget decision divides
 * by or reaches for — is resolved.
 *
 * The `<context_reminder>` threshold, the automatic compaction trigger and the compression input
 * budget all describe the same budget: the reminder fires at a share of it, the compactor at the
 * top of it. When each of them picked its own source they disagreed — the compactor had a
 * fallback for a window nobody published, the reminder did not, so the reminder was silently
 * unreachable on every provider that does not publish one (DeepSeek, Gemini, Qwen, GLM, any
 * OpenAI-compatible gateway), while compaction still ran. One number, one source list.
 *
 * Authority order:
 * 1. [declaredCeiling] — the user's explicit token ceiling (TOKENS threshold mode). It is a
 *    declared model limit, so it wins even over provider metadata.
 * 2. [providerWindow] — what the provider's model list published (OpenRouter `context_length`,
 *    Gemini `inputTokenLimit`).
 * 3. [registryWindow] — the static `ModelRegistry` entry for the model id.
 *
 * Returns null when nothing is known. Callers must then behave conservatively: percent-based
 * features stay off rather than dividing by an invented window and firing at the wrong moment.
 * Pure, so the whole decision table is unit-testable without Android.
 */
object ContextWindowResolver {
    fun resolve(
        declaredCeiling: Int?,
        providerWindow: Int?,
        registryWindow: Int?,
    ): Int? = declaredCeiling?.takeIf { it > 0 }
        ?: providerWindow?.takeIf { it > 0 }
        ?: registryWindow?.takeIf { it > 0 }
}
