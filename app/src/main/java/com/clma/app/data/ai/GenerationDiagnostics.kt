package com.clma.app.data.ai

import com.clma.ai.ui.UIMessagePart
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
