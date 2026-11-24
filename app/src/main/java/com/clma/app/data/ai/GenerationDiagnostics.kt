package com.clma.app.data.ai

import com.clma.ai.core.TokenUsage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.datastore.AutoCompactionThresholdMode
import com.clma.app.data.datastore.Settings
import com.clma.common.android.Logging
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Correlates a compaction with its generating step without persisting conversation IDs. */
internal class GenerationDiagnosticStep(val run: Long, val step: Int) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GenerationDiagnosticStep>
}

/** Only fixed event names and numbers; never tool names, arguments, output or exceptions. */
internal object GenerationDiagnostics {
    private val runs = AtomicLong()
    fun nextRun(): Long = runs.incrementAndGet()

    fun step(run: Long, step: Int, messages: Int, trips: Int) = Logging.log(
        "GenerationDiag", "step run=$run step=$step messages=$messages guard_trips=$trips",
    )

    fun guard(run: Long, step: Int, trips: Int, occurrences: Int) = Logging.log(
        "GenerationDiag", "guard run=$run step=$step trips=$trips occurrences=$occurrences",
    )

    fun tool(run: Long, step: Int, executed: Boolean) = Logging.log(
        "GenerationDiag", "tool run=$run step=$step executed=$executed",
    )

    fun turnSummary(run: Long, finished: Boolean, summary: TurnSummary) = Logging.log(
        "GenerationDiag",
        "turn run=$run finished=$finished events=${summary.eventCount} calls=${summary.toolCallCount} " +
            "completed=${summary.completedToolCallCount} pending=${summary.pendingToolCallIds.size} " +
            "failures=${summary.failureCount} recoveries=${summary.recoveryCount} compactions=${summary.compactionCount}",
    )

    enum class CompactionEntry { PRE_SEND, AFTER_TOOL, IDLE }

    enum class CompactionReason {
        TOKEN_THRESHOLD, BYTE_LIMIT, MEDIA_LIMIT, NO_THRESHOLD, NO_PROMPT_USAGE,
        NO_NEW_USER_TURN, ESTIMATION_FAILED,
    }

    fun compactionDecision(
        entry: CompactionEntry,
        settings: Settings,
        triggerTokens: Int?,
        estimatedTokens: Int?,
        usage: TokenUsage?,
        shouldCompact: Boolean,
        reason: CompactionReason,
        link: GenerationDiagnosticStep? = null,
        hasOlderRawMessages: Boolean? = null,
    ) {
        val mode = settings.autoCompactionThresholdMode
        val configured = when (mode) {
            AutoCompactionThresholdMode.TOKENS -> settings.autoCompactionThresholdTokensK
            AutoCompactionThresholdMode.PERCENT -> settings.autoCompactionThresholdPercent
        }
        Logging.log(
            "GenerationDiag",
            "compaction_decision entry=$entry run=${link?.run ?: 0} step=${link?.step ?: -1} " +
                "mode=$mode configured=$configured trigger_tokens=$triggerTokens estimated_tokens=$estimatedTokens " +
                "usage_prompt=${usage?.promptTokens} usage_completion=${usage?.completionTokens} " +
                "usage_total=${usage?.totalTokens} should_compact=$shouldCompact reason=$reason " +
                "has_older_raw=$hasOlderRawMessages",
        )
    }

    fun compaction(
        link: GenerationDiagnosticStep?,
        automatic: Boolean,
        before: Int,
        after: Int?,
        durationMs: Long,
        outcome: String,
    ) = Logging.log(
        "GenerationDiag",
        "compaction run=${link?.run ?: 0} step=${link?.step ?: -1} automatic=$automatic " +
            "before_est=$before after_est=${after ?: -1} duration_ms=$durationMs " +
            "outcome=$outcome",
    )
}

/** In-memory equality only. The digest and the source content never enter diagnostic logs. */
internal fun toolResultFingerprint(output: List<UIMessagePart>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    output.forEach { part ->
        val text = if (part is UIMessagePart.Text) part.text else part.toString()
        digest.update(text.toByteArray())
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
