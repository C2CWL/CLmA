package me.rerere.rikkahub.data.usage

import me.rerere.ai.core.TokenUsage
import kotlin.coroutines.cancellation.CancellationException

/**
 * P2-11c2 - turns one finished model call into at most one ledger row.
 *
 * Kept apart from [UsageRecordingProvider] so that the decision table (record or skip, and
 * how a storage failure is contained) can be unit tested without a ProviderSetting and
 * without a Room runtime.
 */
internal object UsageCallRecorder {

    sealed interface Outcome {
        /** A row was written. */
        data class Recorded(val row: UsageRecordEntity) : Outcome

        /**
         * The provider reported no usage at all. Nothing is written: a zero row would be a
         * lie, and it would drag every average towards zero.
         */
        data object NoUsage : Outcome

        /** Storage failed. The model call itself is unaffected. */
        data class Failed(val error: Throwable) : Outcome
    }

    suspend fun record(
        ledger: UsageLedger,
        usage: TokenUsage?,
        context: UsageCallContext,
        providerName: String? = null,
        modelId: String? = null,
        costMicros: Long? = null,
        priceVersionId: String? = null,
        streaming: Boolean = false,
        latencyMs: Long? = null,
    ): Outcome {
        if (usage == null) return Outcome.NoUsage
        return try {
            Outcome.Recorded(
                ledger.record(
                    purpose = context.purpose,
                    usage = usage,
                    providerId = providerName,
                    modelId = modelId,
                    costMicros = costMicros,
                    priceVersionId = priceVersionId,
                    assistantId = context.assistantId,
                    conversationId = context.conversationId,
                    runId = context.runId,
                    parentRunId = context.parentRunId,
                    streaming = streaming,
                    latencyMs = latencyMs,
                )
            )
        } catch (e: CancellationException) {
            // Telemetry must not swallow cancellation: the turn is genuinely being torn down.
            throw e
        } catch (e: Throwable) {
            Outcome.Failed(e)
        }
    }
}
