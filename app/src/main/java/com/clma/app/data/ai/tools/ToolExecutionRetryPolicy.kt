package me.rerere.rikkahub.data.ai.tools

import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException

/**
 * T-05 / (3) — execution-layer retry policy for locally dispatched tool calls.
 *
 * The generation *stream* already retries transport failures (`retryWhen` +
 * `shouldRetryGenerationStreamFailure`), and a half-executed turn can be resumed. What was
 * missing is a retry *inside a single tool call*: a read-only tool that hit a one-off socket
 * timeout reported the failure straight back to the model, which then burned a whole extra
 * model round-trip (or gave up) on something a 500ms retry would have fixed.
 *
 * This object is deliberately pure — no Android, no I/O, no globals — so the entire policy is
 * unit-testable:
 *
 *  - [isIdempotentReadOnly] decides WHICH tool calls may be retried. Usually the tool name
 *    settles it; `web_fetch` is the exception, because its HTTP verb lives in the arguments
 *    (T-11). Retrying a write would duplicate its side effect.
 *  - [isTransientFailure] / [isTransientToolOutput] decide WHETHER an outcome is worth
 *    retrying. Deterministic failures (bad args, permission denied, 404) are never retried —
 *    they would fail identically forever.
 *  - [retryDelayMs] is the exponential backoff schedule.
 *  - [runWithRetries] is the loop itself, with its clock and sleep injected so tests can run
 *    it without real time passing.
 *
 * Nothing here is active by default: `GenerationLoop` only routes a call through
 * [runWithRetries] when the assistant opted in via `enableToolExecutionRetry`, and only when
 * [isIdempotentReadOnly] accepts the call.
 */
object ToolExecutionRetryPolicy {

    /** Extra attempts after the first one. Total attempts = `MAX_RETRIES + 1`. */
    const val MAX_RETRIES: Int = 2

    private const val INITIAL_DELAY_MS = 500L
    private const val MAX_DELAY_MS = 4_000L

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Tool-name prefixes whose entire family is a pure read. Verified against the local tool
     * registry under `data/ai/tools/`: every name carrying one of these prefixes is a
     * reader, and no writer shares a prefix. Tools that mix read and write behind a single
     * name (`clipboard_tool`, `workspace_shell`, `memory_tool`, `notification_status`'s
     * mutating siblings, ...) are deliberately absent.
     */
    private val IDEMPOTENT_PREFIXES = listOf(
        "get_",    // get_battery_status / get_wifi_info / get_location / get_volume / ...
        "list_",   // list_files / list_installed_apps / list_contacts / list_zip_contents / ...
        "read_",   // read_file / read_sensor / read_window_tree
        "search_", // search_web / search_contacts / search_sms
        "find_",   // find_files / find_node
        "scrape_", // scrape_web
        // T-11: a "web_" prefix used to sit here (T-05), annotated "HTTP GET only". That was
        // wrong: `web_fetch` dispatches on its `method` argument and accepts
        // POST/PUT/PATCH/DELETE/HEAD as well as GET, so a prefix match classified a write as a
        // pure read and a retry could duplicate its side effect. `web_fetch` is now resolved by
        // [isReadOnlyWebFetchCall], which needs the call's arguments; `web_extract` moved to
        // [IDEMPOTENT_EXACT] because it really is GET-only.
    )

    /**
     * Read-only tools whose names do not carry one of the safe prefixes. Each entry is a
     * single pure read.
     */
    private val IDEMPOTENT_EXACT = setOf(
        "conversation_search",
        "recent_chats",
        "whisper_status",
        "tool_search",
        "notification_status",
        "keyboard_editor_info",
        "keyboard_read_field",
        "file_info",
        "keystore_list_keys",
        "keystore_verify",
        "workspace_read_file",
        "workspace_read_folder",
        "workspace_background_status",
        // T-11: `web_extract` is GET-only, so unlike its sibling `web_fetch` it needs no
        // argument inspection — see [isReadOnlyWebFetchCall].
        "web_extract",
    )

    /**
     * The one local tool whose read/write nature depends on its ARGUMENTS rather than its name:
     * `web_fetch` dispatches on `method` and accepts POST/PUT/PATCH/DELETE as well as GET/HEAD.
     */
    private const val WEB_FETCH_TOOL_NAME = "web_fetch"

    /** The argument that selects the HTTP verb for [WEB_FETCH_TOOL_NAME]. */
    private const val WEB_FETCH_METHOD_ARG = "method"

    /**
     * The verbs that only read. Mirrors the read-only half of `WebFetchTool.HTTP_METHODS`
     * (T-07). Any verb NOT listed here is refused, so a verb added upstream can never silently
     * widen the retry surface — it would have to be added here on purpose.
     */
    private val READ_ONLY_HTTP_METHODS = setOf("GET", "HEAD")

    /**
     * 4xx statuses that describe a *transient* condition rather than a permanently bad
     * request, mirroring `GenerationLoop`'s `RETRYABLE_4XX_STATUS_CODES`. Every 5xx is
     * treated as transient.
     */
    private val RETRYABLE_4XX_STATUS_CODES = setOf(408, 409, 425, 429)

    /**
     * Error codes tools embed in their JSON result envelope when a network operation failed
     * transiently, as emitted under `data/ai/tools/` and `browser/`. Deterministic codes
     * (`bad_url`, `missing_path`, `blocked_address`, `readability_failed`, `not_found`,
     * `permission_denied`, `missing_required_arg`, ...) are deliberately absent — retrying
     * those spends time and requests to reach the same answer.
     */
    private val TRANSIENT_ERROR_CODES = setOf(
        "timeout",
        "tool_timeout",
        "command_timeout",
        "network_error",
        "connect_failed",
        "tcp_unreachable",
        "browser_task_timeout",
        "browser_session_lost",
        "browser_busy",
    )

    private val QUOTA_EXHAUSTED_MARKERS = listOf(
        "resource exhausted",
        "resource has been exhausted",
    )

    private val CONTEXT_LIMIT_MARKERS = listOf(
        "context length exceeded",
        "maximum context length",
        "maximum context window",
    )

    /**
     * True when every call of [toolName] is a pure read, i.e. safe to repeat after a
     * transient failure.
     *
     * MCP-relayed tools (`mcp__<server>__<tool>`) are always excluded: their name carries no
     * information about whether the underlying operation is a read or a write, and the relay
     * cannot be inspected here.
     */
    /**
     * True when this call is a pure read, i.e. safe to repeat after a transient failure.
     *
     * Usually the tool's NAME settles it. `web_fetch` is the exception: it dispatches on its
     * `method` argument, so [args] decides — see [isReadOnlyWebFetchCall]. Callers that cannot
     * supply the arguments get a conservative answer (a skipped retry) rather than a duplicated
     * write.
     *
     * MCP-relayed tools (`mcp__<server>__<tool>`) are always excluded: their name carries no
     * information about whether the underlying operation is a read or a write, and the relay
     * cannot be inspected here.
     */
    fun isIdempotentReadOnly(toolName: String, args: JsonElement? = null): Boolean {
        val name = toolName.trim()
        if (name.isEmpty()) return false
        if (name.startsWith("mcp__") || name.startsWith("mcp_")) return false
        if (name == WEB_FETCH_TOOL_NAME) return isReadOnlyWebFetchCall(args)
        if (name in IDEMPOTENT_EXACT) return true
        return IDEMPOTENT_PREFIXES.any { name.startsWith(it) }
    }

    /**
     * Whether this particular `web_fetch` call is a pure read.
     *
     * Absent `method` → the tool's documented default, GET → eligible.
     *
     * Unusable arguments → **not** eligible, because nothing then proves the verb: `null` args,
     * a JSON value that is not an object, an explicit `{"method": null}`, a non-string verb, or
     * a blank one. The asymmetry is deliberate — refusing a retry costs one extra model
     * round-trip, while retrying a `POST` that already reached the server duplicates whatever it
     * did.
     */
    private fun isReadOnlyWebFetchCall(args: JsonElement?): Boolean {
        val obj = args as? JsonObject ?: return false
        val raw = obj[WEB_FETCH_METHOD_ARG] ?: return true
        if (raw is JsonNull) return false
        val method = (raw as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
        if (method.isNullOrEmpty()) return false
        return method in READ_ONLY_HTTP_METHODS
    }

    internal fun isTransientHttpStatus(statusCode: Int): Boolean =
        statusCode in RETRYABLE_4XX_STATUS_CODES || statusCode in 500..599

    /**
     * True when [failure] describes a condition that a retry could plausibly resolve.
     *
     * Walks up to eight levels of the cause chain and returns on the first decisive link.
     * Cancellation, quota exhaustion, context-limit and missing-file failures veto a retry
     * outright, wherever they appear in the chain.
     */
    fun isTransientFailure(failure: Throwable): Boolean {
        val chain = causeChain(failure)
        if (chain.any { it is CancellationException }) return false
        if (chain.any { it is FileNotFoundException }) return false
        if (isQuotaExhaustedFailure(chain)) return false
        if (isContextLimitFailure(chain)) return false
        for (cause in chain) {
            when {
                cause is HttpException ->
                    return cause.statusCode?.let(::isTransientHttpStatus) ?: true
                // SocketTimeoutException / InterruptedIOException / UnknownHostException /
                // ConnectException / NoRouteToHostException / SocketException are all
                // IOException subclasses, so this one branch covers every network-shape
                // failure. FileNotFoundException is vetoed above before we get here.
                cause is IOException -> return true
            }
        }
        return false
    }

    /**
     * True when [output] carries a transient failure in the shape tools actually use.
     *
     * This matters more than the exception path: most local network tools deliberately do NOT
     * throw — they catch OkHttp's `IOException` and return `{"error":"timeout"}` /
     * `{"error":"network_error"}` — so an exception-only retry would never fire for the very
     * tools this feature exists for.
     */
    fun isTransientToolOutput(output: List<UIMessagePart>): Boolean =
        output.any { part -> part is UIMessagePart.Text && isTransientToolText(part.text) }

    internal fun isTransientToolText(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return false
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return false
        val errorCode = (obj["error"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (errorCode != null && errorCode in TRANSIENT_ERROR_CODES) return true
        // web_fetch / web_extract report the HTTP status directly (`{status, ok}`) instead of
        // an `error` code; a 5xx / 429 there is the same transient condition.
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull
        val status = (obj["status"] as? JsonPrimitive)?.intOrNull
        return ok == false && status != null && isTransientHttpStatus(status)
    }

    /** Exponential backoff: 500ms, 1s, 2s, 4s (capped). [retryNumber] is 1-based. */
    fun retryDelayMs(retryNumber: Int): Long {
        val shift = (retryNumber - 1).coerceIn(0, 4)
        return (INITIAL_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }

    /** Terminal state of a retried tool call. */
    internal sealed interface RetryOutcome {
        /** The tool produced [output]; hand it to the model unchanged. */
        data class Completed(val output: List<UIMessagePart>, val attempts: Int) : RetryOutcome

        /** The call still failed after [attempts] attempts. */
        data class Failed(val failure: Throwable, val attempts: Int) : RetryOutcome

        /** An attempt was cut off mid-execution by the caller's wall-clock budget. */
        data class TimedOut(val attempts: Int) : RetryOutcome

        /** The wall-clock budget was already spent before an attempt could start. */
        data class BudgetExhausted(val attempts: Int) : RetryOutcome
    }

    /**
     * Runs [execute] and retries while the outcome looks transient, up to [MAX_RETRIES] extra
     * attempts.
     *
     * [execute] receives the wall-clock budget left for this attempt and returns `null` when
     * the attempt was cut off mid-flight (the caller's `withTimeoutOrNull` timed out) — that
     * is terminal, never retried, because the budget is spent.
     * [remainingMsProvider] is re-read before each attempt; returning a non-positive value
     * stops the loop with [RetryOutcome.BudgetExhausted].
     */
    internal suspend fun runWithRetries(
        firstAttemptRemainingMs: Long,
        remainingMsProvider: () -> Long,
        execute: suspend (budgetMs: Long) -> List<UIMessagePart>?,
        sleep: suspend (Long) -> Unit = { delay(it) },
        onRetry: (attemptNumber: Int, failure: Throwable?) -> Unit = { _, _ -> },
    ): RetryOutcome {
        var attempt = 0
        var remainingMs = firstAttemptRemainingMs
        while (true) {
            if (remainingMs <= 0L) return RetryOutcome.BudgetExhausted(attempt)

            var output: List<UIMessagePart>? = null
            var failure: Throwable? = null
            try {
                output = execute(remainingMs)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                failure = t
            }

            if (failure == null && output == null) {
                // The attempt itself was cut off by the caller's timeout — terminal.
                return RetryOutcome.TimedOut(attempt + 1)
            }

            val completed = output
            val transient = if (failure != null) {
                isTransientFailure(failure)
            } else {
                isTransientToolOutput(completed.orEmpty())
            }
            if (attempt >= MAX_RETRIES || !transient) {
                return if (failure != null) {
                    RetryOutcome.Failed(failure, attempt + 1)
                } else {
                    RetryOutcome.Completed(completed.orEmpty(), attempt + 1)
                }
            }

            attempt++
            onRetry(attempt, failure)
            sleep(retryDelayMs(attempt))
            remainingMs = remainingMsProvider()
        }
    }

    private fun causeChain(failure: Throwable): List<Throwable> =
        generateSequence(failure) { it.cause }.take(8).toList()

    private fun normalize(failure: Throwable): String =
        (failure.message.orEmpty() + " " + failure.toString())
            .lowercase()
            .replace('_', ' ')

    // Same semantics as GenerationLoop's private helpers of the same name: a 429 that also
    // carries a RESOURCE_EXHAUSTED marker is a server-side quota block, not ordinary rate
    // limiting, so retrying only burns time and requests.
    private fun isQuotaExhaustedFailure(chain: List<Throwable>): Boolean {
        if (chain.filterIsInstance<HttpException>().none { it.statusCode == 429 }) return false
        return chain.any { cause -> QUOTA_EXHAUSTED_MARKERS.any { it in normalize(cause) } }
    }

    private fun isContextLimitFailure(chain: List<Throwable>): Boolean =
        chain.any { cause -> CONTEXT_LIMIT_MARKERS.any { it in normalize(cause) } }
}
