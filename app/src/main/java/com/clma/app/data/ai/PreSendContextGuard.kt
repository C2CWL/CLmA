package com.clma.app.data.ai

import com.clma.ai.core.MessageRole
import com.clma.ai.provider.Model
import com.clma.ai.registry.ModelRegistry
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.ai.util.MediaLimits
import kotlinx.datetime.LocalDateTime

/** What to do with a new user turn before its request goes out. */
sealed interface PreSendContextDecision {
    /** Send as is. */
    data object Send : PreSendContextDecision

    /** Automatic compaction is on and the request already reaches its trigger: compact first. */
    data class Compact(val estimatedTokens: Int, val triggerTokens: Int) : PreSendContextDecision

    /**
     * Automatic compaction is off and the request is clearly larger than the model's window: it
     * would only come back as a "context too long" error (and be paid for), so do not send it.
     */
    data class OverHardLimit(val estimatedTokens: Int, val limitTokens: Int) : PreSendContextDecision

    /**
     * The request would be too large to build in memory (text plus base64 media), independent
     * of tokens: images count as ~1K tokens each but can be MBs of base64. [compact] is true when
     * automatic compaction is on and should run first; otherwise the turn is refused.
     */
    data class OverByteLimit(
        val estimatedBytes: Long,
        val limitBytes: Long,
        val compact: Boolean,
    ) : PreSendContextDecision

    /** A video/audio file in the message being sent is over [MediaLimits.MAX_INLINE_MEDIA_BYTES]. */
    data class MediaTooLarge(val fileName: String, val sizeBytes: Long) : PreSendContextDecision
}

/** True when the decision asks for a compaction before sending. */
val PreSendContextDecision.shouldCompact: Boolean
    get() = this is PreSendContextDecision.Compact ||
        (this is PreSendContextDecision.OverByteLimit && compact)

/**
 * Pre-send check for a new user turn.
 *
 * Automatic compaction otherwise only runs after a tool result (driven by provider-reported
 * usage) or after the provider already rejected a request as too long. A turn that starts over
 * the window - a long chat, a big attachment, the "继续" after a failed reply - was therefore
 * always sent, and always failed.
 *
 * Why this is cheap and does not over-trigger, which is why the original code avoided
 * estimating here:
 * - It only runs at the start of a new user turn (the last message is the user's). Tool
 *   continuations and approvals inside a turn keep the usage-driven path, so the model still
 *   gets to run its tools before anything is compacted.
 * - [ContextBudgetPlanner.estimateInputTokens] anchors on the newest provider-reported usage
 *   (which already includes system prompt and tool schemas) and only estimates the messages after
 *   it - typically just the new user message - so it is both accurate and O(new messages).
 * - After a compaction, usage reported before it describes the old, larger request; it is then
 *   ignored and the compacted view is estimated directly, the same check the compaction code
 *   uses to size its tail, so a fresh compaction does not immediately trigger another one.
 * - The hard-limit refusal only fires [HARD_LIMIT_TOLERANCE_PERCENT] over the known window, so
 *   the conservative estimator or a slightly rounded registry value cannot block a request
 *   that would have fit.
 */
object PreSendContextGuard {
    const val HARD_LIMIT_TOLERANCE_PERCENT = 110

    /** Upper bound of the request-size cap: 64 MB. */
    const val MAX_REQUEST_BYTES_CEILING: Long = 64L * 1024 * 1024

    /**
     * An image is re-encoded as JPEG (longest edge 4096 px) before it is sent, so its base64 is
     * usually far smaller than the original file (a 12 MB PNG screenshot becomes ~1-2 MB). Count
     * at most this much per image instead of the raw file size.
     */
    const val MAX_ESTIMATED_IMAGE_BYTES: Long = 4L * 1024 * 1024

    /** Documents are inlined at most up to the context window; count at most this much each. */
    const val MAX_ESTIMATED_DOCUMENT_BYTES: Long = 8L * 1024 * 1024

    /** min(64 MB, heap / 6): building a request briefly needs a few times its size. */
    fun defaultMaxRequestBytes(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Long =
        minOf(MAX_REQUEST_BYTES_CEILING, maxHeapBytes / 6)

    /**
     * Rough in-memory size of the request built from [messages]: text characters plus media file
     * sizes x 4/3 (base64). Applies the same request-only image ageing the generation loop does
     * ([ageOldToolImages], [ageOldUserImages]) so aged-out images are not counted. [fileSize]
     * returns the size of a `file:` URL, or null when unknown.
     */
    @Suppress("DEPRECATION")
    fun estimateRequestBytes(messages: List<UIMessage>, fileSize: (String) -> Long?): Long {
        fun base64(bytes: Long) = bytes / 3 * 4 + 4
        fun media(url: String, cap: Long = Long.MAX_VALUE): Long = when {
            url.startsWith("data:") || url.startsWith("http") -> url.length.toLong()
            else -> base64(minOf(fileSize(url) ?: 0L, cap))
        }
        fun part(part: UIMessagePart): Long = when (part) {
            is UIMessagePart.Text -> part.text.length.toLong()
            is UIMessagePart.Reasoning -> part.reasoning.length.toLong()
            is UIMessagePart.Image -> media(part.url, MAX_ESTIMATED_IMAGE_BYTES)
            is UIMessagePart.Video -> media(part.url)
            is UIMessagePart.Audio -> media(part.url)
            is UIMessagePart.Document -> minOf(fileSize(part.url) ?: 0L, MAX_ESTIMATED_DOCUMENT_BYTES)
            is UIMessagePart.Tool -> part.input.length.toLong() + part.output.sumOf(::part)
            is UIMessagePart.ToolCall -> part.arguments.length.toLong()
            is UIMessagePart.ToolResult -> part.content.toString().length.toLong()
            else -> 0L
        }
        return messages.ageOldToolImages().ageOldUserImages().sumOf { message ->
            message.parts.sumOf(::part)
        }
    }

    /** The first video/audio in the last (user) message whose file is over the inline limit. */
    fun oversizedMedia(messages: List<UIMessage>, fileSize: (String) -> Long?): PreSendContextDecision.MediaTooLarge? {
        val last = messages.lastOrNull() ?: return null
        last.parts.forEach { part ->
            val (url, name) = when (part) {
                is UIMessagePart.Video -> part.url to part.url.substringAfterLast('/')
                is UIMessagePart.Audio -> part.url to part.url.substringAfterLast('/')
                else -> return@forEach
            }
            val size = fileSize(url) ?: return@forEach
            if (size > MediaLimits.MAX_INLINE_MEDIA_BYTES) {
                return PreSendContextDecision.MediaTooLarge(name, size)
            }
        }
        return null
    }

    /**
     * The model's real window for the hard-limit check: provider metadata, else the registry.
     * Deliberately ignores the TOKENS-mode threshold, which is a compaction trigger the user
     * picked (8K by default), not a model limit.
     */
    fun hardContextLimit(model: Model): Int? = ContextWindowResolver.resolve(
        declaredCeiling = null,
        providerWindow = model.contextLength,
        registryWindow = ModelRegistry.MODEL_CONTEXT_LENGTH.getData(model.modelId),
    )

    /**
     * Estimated input tokens of [messages] (the request view). When [compactedAt] is set, usage
     * reported by a message created before that compaction is not trusted.
     */
    fun estimateTokens(messages: List<UIMessage>, compactedAt: LocalDateTime?): Int {
        if (compactedAt == null) return ContextBudgetPlanner.estimateInputTokens(messages)
        val latestUsage = messages.lastOrNull { message ->
            val usage = message.usage
            usage != null && (usage.totalTokens > 0 || usage.promptTokens + usage.completionTokens > 0)
        }
        return if (latestUsage != null && latestUsage.createdAt >= compactedAt) {
            ContextBudgetPlanner.estimateInputTokens(messages)
        } else {
            ContextBudgetPlanner.estimateContextTokens(messages)
        }
    }

    fun decide(
        messages: List<UIMessage>,
        autoCompactionEnabled: Boolean,
        triggerTokens: Int?,
        hardLimitTokens: Int?,
        compactedAt: LocalDateTime? = null,
        // Byte-size checks run only when a file-size lookup is supplied.
        fileSize: ((String) -> Long?)? = null,
        maxRequestBytes: Long = defaultMaxRequestBytes(),
    ): PreSendContextDecision {
        // Only a brand-new user turn; tool resumes / approvals keep the usage-driven path.
        if (messages.lastOrNull()?.role != MessageRole.USER) return PreSendContextDecision.Send
        if (fileSize != null) {
            oversizedMedia(messages, fileSize)?.let { return it }
            val bytes = estimateRequestBytes(messages, fileSize)
            if (bytes > maxRequestBytes) {
                return PreSendContextDecision.OverByteLimit(
                    estimatedBytes = bytes,
                    limitBytes = maxRequestBytes,
                    compact = autoCompactionEnabled,
                )
            }
        }
        if (autoCompactionEnabled) {
            val trigger = triggerTokens?.takeIf { it > 0 } ?: return PreSendContextDecision.Send
            val estimated = estimateTokens(messages, compactedAt)
            return if (estimated >= trigger) {
                PreSendContextDecision.Compact(estimated, trigger)
            } else {
                PreSendContextDecision.Send
            }
        }
        val limit = hardLimitTokens?.takeIf { it > 0 } ?: return PreSendContextDecision.Send
        val estimated = estimateTokens(messages, compactedAt)
        val refuseAbove = limit.toLong() * HARD_LIMIT_TOLERANCE_PERCENT / 100L
        return if (estimated > refuseAbove) {
            PreSendContextDecision.OverHardLimit(estimated, limit)
        } else {
            PreSendContextDecision.Send
        }
    }
}
