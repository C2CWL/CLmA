package me.rerere.rikkahub.subagent

import kotlinx.serialization.Serializable

/**
 * Phase 11 — sub-agent run record. Lives in [SubAgentRegistry]'s in-memory map for the
 * lifetime of the app process. Persistence intentionally out of scope for v1: spec says
 * "Background sub-agents survive only as long as the parent process is alive" and
 * documents that user-visibly. WorkManager-backed persistence is a v2 concern.
 *
 * The run is FROZEN once it reaches a terminal status. Mutations are done by replacing
 * the entry in the registry's StateFlow rather than mutating in place.
 */
@Serializable
data class SubAgentRun(
    val id: String,
    val parentChatId: String?,         // the parent assistant chat that dispatched this — used for /stop cascade
    val parentAssistantId: String,
    val label: String,
    val task: String,
    val modelId: String?,              // null = inherited from parent
    val tools: List<String>?,          // null = inherited from parent; T-09: also the narrowing
                                       // allow-list a sub-agent conversation is frozen to
    val runInBackground: Boolean,
    val noResult: Boolean = false,   // #78/#79: suppress result text from encodeRun/parent notification
    val timeoutSeconds: Int,
    val maxTrips: Int,
    val status: SubAgentStatus,
    val result: String? = null,
    val error: String? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val tripCount: Int = 0,
    /** P2-13 — model round trips this run billed, read back from the ledger when it ends. */
    val usageCalls: Int = 0,
    /** D3 — how many parent audio/video attachments travelled with this dispatch (0 = none). */
    val mediaParts: Int = 0,
    /** The dispatch's `reasoning_level`, echoed for observability; null = inherited. */
    val reasoningLevel: String? = null,
    /**
     * How many times the engine retried the generation after a non-cancellation failure.
     * 0 = succeeded on the first attempt (or was never retried). Reported in [encodeRun]
     * so the dispatcher can see whether retries were needed.
     */
    val retryCount: Int = 0,
)

@Serializable
enum class SubAgentStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

object SubAgentDefaults {
    /**
     * Default per-run wall-clock cap used when a dispatch omits `timeout_seconds`. A foreground
     * dispatch blocks the parent's tool call for the whole run, so the old 300s default was the
     * single biggest contributor to "the tool call looks hung" for several minutes. A caller can
     * still ask for up to [MAX_TIMEOUT_SECONDS] explicitly.
     */
    const val DEFAULT_TIMEOUT_SECONDS = 180
    const val MAX_TIMEOUT_SECONDS = 1800
    const val DEFAULT_MAX_TRIPS = 12
    const val MAX_MAX_TRIPS = 30
    const val MAX_LABEL_LENGTH = 60
    /** Compiled-in default; the live ceiling is `Settings.subAgentGlobalConcurrencyCap`. */
    const val GLOBAL_CONCURRENCY_CAP = 30
    const val MIN_GLOBAL_CONCURRENCY_CAP = 8
    const val MAX_GLOBAL_CONCURRENCY_CAP = 64
    const val MIN_PER_ASSISTANT_CAP = 1
    const val MAX_PER_ASSISTANT_CAP = 16
    const val REGISTRY_LRU_CAP = 50

    /**
     * Accepted values for `subagent_dispatch`'s `reasoning_level` — the lowercase serial names
     * of `me.rerere.ai.core.ReasoningLevel`, listed here so [SubAgentRequestValidator] stays
     * free of the :ai module. The local test harness pins this list against the real enum, so
     * a new level added upstream fails loudly instead of silently being rejected here.
     */
    val VALID_REASONING_LEVELS = listOf("off", "auto", "low", "medium", "high", "xhigh", "max")
    /**
     * How long a dispatch waits for a free concurrency slot before refusing. The engine's own doc
     * has always promised "block up to 30s waiting for a slot"; before the bounded wait existed an
     * over-cap dispatch simply failed, so a parallel burst lost every run past the cap.
     */
    const val SLOT_WAIT_TIMEOUT_MS = 30_000L
    const val SLOT_RETRY_INTERVAL_MS = 250L

    /**
     * How many times a sub-agent retries its generation after a non-cancellation failure
     * (all internal retries + model routing exhausted). Each retry re-runs the generation
     * via [me.rerere.rikkahub.service.ChatService.regenerateAtMessage], which means each
     * retry also benefits from model routing — a model that failed is in cooldown, so the
     * next attempt automatically tries a different favourite model.
     *
     * Default 1 (one retry) so a transient network blip does not waste a whole sub-agent run;
     * 0 preserves the pre-retry behaviour (fail immediately).
     */
    const val DEFAULT_RETRY_ON_FAILURE = 1
    const val MAX_RETRY_ON_FAILURE = 5

    /** Default system prompt used when the assistant's per-sub-agent prompt is empty. */
    val DEFAULT_SYSTEM_PROMPT = """
        You are a focused sub-agent dispatched by a parent assistant to complete a single
        task and return a concise summary.

        Rules:
        - Stay tightly scoped to the task you were given. Do not expand scope.
        - Use tools to gather facts before answering when accuracy matters.
        - Return a clear, structured final summary as your last message — that summary is
          what the parent will see. Aim for 100-500 words unless the task asks otherwise.
        - If the task is impossible, return a single short paragraph explaining why.
        - Do not ask the parent for clarification — make the best judgment call you can
          and proceed.
    """.trimIndent()
}

@Serializable
data class SubAgentRequest(
    val task: String,
    val modelId: String? = null,
    /**
     * #36, re-homed onto the expert library by P2-06b: name of a stored
     * [me.rerere.rikkahub.data.agentdef.AgentDefinition], resolved case-insensitively by
     * [me.rerere.rikkahub.data.agentdef.AgentDefinitionResolver]. `modelId` above wins over the
     * expert's model when both are given; see [SubAgentEngine.executeRun].
     */
    val agentName: String? = null,
    val systemPrompt: String? = null,
    /**
     * T-09 / (8): optional allow-list of tool names for the run. Null (or an empty array) means
     * "no narrowing" — the sub-agent inherits the frozen headless-safe surface.
     *
     * This used to be a dead parameter: it was parsed out of the tool call, stored here, and
     * never read by anything (the engine copied it into the run record and stopped). Only honoured
     * while the assistant has `enableSubAgentToolSurface` on; with the flag off a caller-supplied
     * list is ignored exactly as before.
     */
    val tools: List<String>? = null,
    val runInBackground: Boolean = false,
    val noResult: Boolean = false,
    val timeoutSeconds: Int = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
    val maxTrips: Int = SubAgentDefaults.DEFAULT_MAX_TRIPS,
    /**
     * Per-run thinking depth: one of [SubAgentDefaults.VALID_REASONING_LEVELS], validated by
     * [SubAgentRequestValidator]. Null = inherit the assistant's own reasoning level (the
     * pre-feature behaviour). Applied through
     * [me.rerere.rikkahub.data.ai.limits.ConversationReasoningOverrides] for exactly the run's
     * lifetime; providers whose model lacks the reasoning ability ignore it downstream.
     */
    val reasoningLevel: String? = null,
    val label: String? = null,
    /**
     * T-04 / (4): references to the CALLER's context. A sub-agent runs in a clean context,
     * so the referenced history is materialised as text into its first user message - the
     * referenced messages are never replayed as real turns, and the sub-agent gains no
     * ability to read the parent conversation itself.
     *
     * Null (the default) means "behave exactly as before T-04": the task text alone.
     * Appended last on purpose, same rule as the Assistant model - every existing caller
     * uses named arguments, and a new field at the end cannot re-bind an older positional
     * one.
     */
    val contextRefs: SubAgentContextRefs? = null,
    /**
     * D3 — when true, the audio/video attachments of the NEWEST user message in the caller's
     * conversation travel into the sub-agent's first message, so an omni-capable child can
     * hear/see what the user just sent. Off by default (task text alone, pre-D3 behaviour) and
     * images are still never carried. Appended last, same rule as [contextRefs]: every existing
     * caller uses named arguments, so a new tail field cannot re-bind an older positional one.
     */
    val attachParentMedia: Boolean = false,
    /**
     * How many times to retry the generation after a non-cancellation failure (all internal
     * retries + model routing exhausted). 0 = no retry (fail immediately, the pre-feature
     * behaviour). Each retry re-runs the generation from the original user message, and each
     * retry benefits from model routing — a model that failed is in cooldown, so the next
     * attempt tries a different favourite model.
     *
     * Appended last, same rule as [attachParentMedia]: every existing caller uses named
     * arguments, so a new tail field cannot re-bind an older positional one.
     */
    val retryOnFailure: Int = SubAgentDefaults.DEFAULT_RETRY_ON_FAILURE,
)

/**
 * T-04 / (4) - which slice of the caller's conversation to hand to the sub-agent.
 *
 * `recentTurns` is the whole of v1. Message IDS were deliberately left out: the
 * dispatching model has no way to learn message ids (they are not in the transcript, the
 * tool schema, or any listing tool), so a `message_ids` parameter would be a parameter no
 * caller could ever fill in correctly - a footgun, not a feature. If a future caller does
 * have real ids (a workflow step, an external automation) the engine can grow an overload;
 * the model-facing surface stays turn-based.
 */
@Serializable
data class SubAgentContextRefs(
    /** How many of the newest caller turns to carry. 0 (or an absent [SubAgentRequest.contextRefs]) = none. */
    val recentTurns: Int = 0,
)

object SubAgentRequestValidator {

    sealed class Result {
        data class Ok(val request: SubAgentRequest) : Result()
        data class Reject(val error: String, val detail: String) : Result()
    }

    fun validate(request: SubAgentRequest): Result {
        val task = request.task.trim()
        if (task.isEmpty()) {
            return Result.Reject("invalid_task", "task is required and may not be blank")
        }
        if (request.timeoutSeconds < 1) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds must be at least 1; got ${request.timeoutSeconds}"
            )
        }
        if (request.timeoutSeconds > SubAgentDefaults.MAX_TIMEOUT_SECONDS) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds exceeds max ${SubAgentDefaults.MAX_TIMEOUT_SECONDS}; got ${request.timeoutSeconds}"
            )
        }
        if (request.maxTrips < 1) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips must be at least 1; got ${request.maxTrips}"
            )
        }
        if (request.maxTrips > SubAgentDefaults.MAX_MAX_TRIPS) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips exceeds max ${SubAgentDefaults.MAX_MAX_TRIPS}; got ${request.maxTrips}"
            )
        }
        request.label?.let {
            if (it.length > SubAgentDefaults.MAX_LABEL_LENGTH) {
                return Result.Reject(
                    "invalid_label",
                    "label exceeds ${SubAgentDefaults.MAX_LABEL_LENGTH} chars; got ${it.length}"
                )
            }
        }
        // T-04: reject rather than clamp, so a caller that asked for 50 turns learns the
        // real ceiling instead of silently getting 10 and assuming it worked.
        request.contextRefs?.recentTurns?.let { turns ->
            if (turns < 0 || turns > SubAgentContextDigest.MAX_TURNS) {
                return Result.Reject(
                    "invalid_context_refs",
                    "context_refs.recent_turns must be between 0 and " +
                        "${SubAgentContextDigest.MAX_TURNS}; got $turns"
                )
            }
        }
        // Reject rather than coerce: a caller that asked for "ultra" should learn the real
        // vocabulary instead of silently running at the assistant's default depth.
        request.reasoningLevel?.let { level ->
            if (level !in SubAgentDefaults.VALID_REASONING_LEVELS) {
                return Result.Reject(
                    "invalid_reasoning_level",
                    "reasoning_level must be one of " +
                        SubAgentDefaults.VALID_REASONING_LEVELS.joinToString(", ") +
                        "; got \"$level\""
                )
            }
        }
        if (request.retryOnFailure < 0) {
            return Result.Reject(
                "invalid_retry_on_failure",
                "retry_on_failure must be at least 0; got ${request.retryOnFailure}"
            )
        }
        if (request.retryOnFailure > SubAgentDefaults.MAX_RETRY_ON_FAILURE) {
            return Result.Reject(
                "invalid_retry_on_failure",
                "retry_on_failure exceeds max ${SubAgentDefaults.MAX_RETRY_ON_FAILURE}; got ${request.retryOnFailure}"
            )
        }
        return Result.Ok(request.copy(task = task))
    }
}
