package me.rerere.rikkahub.data.usage

import android.util.Log
import kotlinx.coroutines.flow.Flow
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage

private const val TAG = "UsageLedger"

/**
 * P2-11c2 - the single collection point.
 *
 * Wraps whatever provider [me.rerere.ai.provider.ProviderManager] would have returned, so
 * every text generation in the app - chat turn, tool-loop follow-ups, title, suggestion,
 * compaction, translation - is accounted for without changing a single call site.
 *
 * Only generateText is wrapped: it is the method all of those paths ultimately call, and it
 * returns the finished message with the provider-reported usage attached. The wrapper is
 * transparent: the delegate result is returned untouched, and a ledger failure is contained
 * here, because a chat turn must never fail because telemetry did.
 */
internal class UsageRecordingProvider<T : ProviderSetting>(
    private val delegate: Provider<T>,
    private val ledger: UsageLedger,
) : Provider<T> by delegate {

    override suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val startedAt = System.currentTimeMillis()
        val result = delegate.generateText(providerSetting, messages, params)
        recordCall(
            usage = result.usage,
            context = currentUsageCallContext() ?: UsageCallContext(),
            providerSetting = providerSetting,
            params = params,
            startedAt = startedAt,
            streaming = false,
        )
        return result
    }

    /**
     * P2-12a - the streaming half of the same accounting.
     *
     * Before this override existed, interface delegation forwarded `streamText` verbatim, so a
     * streamed call - which is the default chat path (`Assistant.streamOutput = true`) - wrote
     * no ledger row at all. The provider reports usage as a `StreamChunk.Usage` inside the
     * flow, so the row is produced by wrapping that flow (see `recordUsageOnce`) instead of by
     * changing the caller's collection.
     *
     * The ambient [UsageCallContext] is read **eagerly**, here, and not inside the flow body:
     * the call site installs it around the `streamText(...)` invocation, while the flow may be
     * collected later (and re-collected by a retry chain), where that context is gone.
     */
    override suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        val startedAt = System.currentTimeMillis()
        val context = currentUsageCallContext() ?: UsageCallContext()
        return delegate.streamText(providerSetting, messages, params).recordUsageOnce(
            select = { chunk -> (chunk as? StreamChunk.Usage)?.usage },
            record = { usage ->
                recordCall(
                    usage = usage,
                    context = context,
                    providerSetting = providerSetting,
                    params = params,
                    startedAt = startedAt,
                    streaming = true,
                )
            },
        )
    }

    /**
     * Prices a finished call and freezes the result into the ledger: editing the price table
     * later must not rewrite what this call already cost. Unpriced models yield null, never
     * zero. Shared by the non-stream and stream paths so both keep one set of rules.
     */
    private suspend fun recordCall(
        usage: TokenUsage?,
        context: UsageCallContext,
        providerSetting: T,
        params: TextGenerationParams,
        startedAt: Long,
        streaming: Boolean,
    ) {
        val price = UsagePriceResolver.ratesAt(
            pricing = params.model.pricing,
            atEpochMs = startedAt,
            fallbackInputPerToken = params.model.pricePromptPerToken,
            fallbackOutputPerToken = params.model.priceCompletionPerToken,
        )
        val outcome = UsageCallRecorder.record(
            ledger = ledger,
            usage = usage,
            context = context,
            providerName = providerSetting.name,
            modelId = params.model.modelId,
            costMicros = usage?.let { UsagePriceResolver.costMicros(it, price?.rates) },
            priceVersionId = UsagePriceResolver.priceVersionId(price),
            streaming = streaming,
            latencyMs = System.currentTimeMillis() - startedAt,
        )
        if (outcome is UsageCallRecorder.Outcome.Failed) {
            Log.w(TAG, "usage ledger write failed for model ${params.model.modelId}", outcome.error)
        }
    }
}

/**
 * ProviderManager hands out star-projected providers while the decorator is generic over the
 * setting type, so the capture happens here once instead of at every call site. The cast is
 * the same unchecked one ProviderManager itself performs when it maps a setting to a
 * registered provider.
 */
@Suppress("UNCHECKED_CAST")
internal fun decorateForUsage(provider: Provider<*>, ledger: UsageLedger): Provider<*> =
    UsageRecordingProvider(provider as Provider<ProviderSetting>, ledger)
