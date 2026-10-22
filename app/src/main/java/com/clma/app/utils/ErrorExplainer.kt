package com.clma.app.utils

import androidx.annotation.StringRes
import kotlinx.serialization.SerializationException
import com.clma.app.R
import com.clma.ai.util.isMediaTooLarge
import com.clma.ai.util.isRequestTooLarge
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * Turns a raw [Throwable] into something a non-technical user can act on.
 *
 * The point is that the chat surface should never show `java.net.UnknownHostException:
 * Unable to resolve host "..."` as the primary message. Users get a plain-language title,
 * one sentence of likely cause, and at most one button that goes somewhere useful. The raw
 * text is still available under "details" so nothing is lost for troubleshooting.
 *
 * Matching is heuristic: exception types first, then keyword hints in the message. Providers
 * word their errors differently, so every explanation is a best guess — callers should show
 * [UserFacingError] together with the "for reference only" disclaimer string.
 */
data class UserFacingError(
    @StringRes val title: Int,
    @StringRes val reason: Int,
    val action: ErrorAction? = null,
    /** Raw exception text, shown under "details" and used by the copy button. */
    val detail: String,
)

/** Primary button on the error card. Only deterministic destinations — never a no-op tap. */
sealed interface ErrorAction {
    data object OpenModelSettings : ErrorAction
    data object OpenLogs : ErrorAction
}

private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

private val RATE_LIMIT_HINTS = listOf(
    "429", "rate limit", "too many requests", "rate_limit_exceeded", "频率", "限流",
)
private val QUOTA_HINTS = listOf(
    "insufficient_quota", "quota", "billing", "payment", "credit", "余额", "欠费", "额度",
)
private val AUTH_HINTS = listOf(
    "401", "invalid api key", "incorrect api key", "unauthorized", "authentication",
    "api key", "apikey", "鉴权", "未授权", "密钥",
)
private val FORBIDDEN_HINTS = listOf(
    "403", "forbidden", "permission denied", "no permission", "access denied",
)
private val MODEL_HINTS = listOf(
    "404", "model not found", "does not exist", "no such model", "invalid model", "unknown model",
)
private val CONTEXT_HINTS = listOf(
    "context length", "context_length", "maximum context", "too many tokens",
    "token limit", "prompt is too long", "max_tokens", "上下文",
)
private val SERVER_HINTS = listOf(
    "500", "502", "503", "504", "internal server error", "bad gateway",
    "service unavailable", "gateway timeout", "overloaded",
)

private fun String.containsAny(hints: List<String>): Boolean =
    hints.any { hint -> if (hint.all { it.isDigit() }) Regex("\\b$hint\\b").containsMatchIn(this) else contains(hint) }

fun Throwable.explainForUser(): UserFacingError {
    val detail = buildString {
        append(this@explainForUser.javaClass.simpleName)
        message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    }
    val chain = causeChain().toList()
    // Join every message in the chain: a wrapped IOException often carries the useful text.
    val text = chain.mapNotNull { it.message }.joinToString(" | ").lowercase()

    return when {
        // --- the request itself did not fit in memory: retrying cannot help --------------
        this@explainForUser.isMediaTooLarge() -> UserFacingError(
            R.string.error_explain_media_too_large_title,
            R.string.error_explain_media_too_large_reason,
            null,
            detail,
        )

        this@explainForUser.isRequestTooLarge() -> UserFacingError(
            R.string.error_explain_request_too_large_title,
            R.string.error_explain_request_too_large_reason,
            null,
            detail,
        )

        // --- transport-level failures: the type itself is unambiguous -------------------
        chain.any { it is UnknownHostException } -> UserFacingError(
            R.string.error_explain_network_title,
            R.string.error_explain_network_reason,
            ErrorAction.OpenModelSettings,
            detail,
        )

        chain.any { it is ConnectException } -> UserFacingError(
            R.string.error_explain_connect_title,
            R.string.error_explain_connect_reason,
            ErrorAction.OpenModelSettings,
            detail,
        )

        chain.any { it is SocketTimeoutException || it is TimeoutException } -> UserFacingError(
            R.string.error_explain_timeout_title,
            R.string.error_explain_timeout_reason,
            ErrorAction.OpenModelSettings,
            detail,
        )

        chain.any { it is SSLException } -> UserFacingError(
            R.string.error_explain_ssl_title,
            R.string.error_explain_ssl_reason,
            ErrorAction.OpenLogs,
            detail,
        )

        // --- provider-side semantics: only the message tells us anything ---------------
        text.containsAny(QUOTA_HINTS) -> UserFacingError(
            R.string.error_explain_quota_title,
            R.string.error_explain_quota_reason,
            ErrorAction.OpenModelSettings,
            detail,
        )

        text.containsAny(RATE_LIMIT_HINTS) -> UserFacingError(
            R.string.error_explain_rate_limit_title,
            R.string.error_explain_rate_limit_reason,
            ErrorAction.OpenModelSettings,
            detail,
        )

        text.containsAny(AUTH_HINTS) -> UserFacingError(
            R.string.error_explain_auth_title,
            R.string.error_explain_auth_reason,
            ErrorAction.OpenModelSettings,
            detail,
        )

        text.containsAny(FORBIDDEN_HINTS) -> UserFacingError(
            R.string.error_explain_forbidden_title,
            R.string.error_explain_forbidden_reason,
            ErrorAction.OpenModelSettings,
            detail,
        )

        text.containsAny(CONTEXT_HINTS) -> UserFacingError(
            R.string.error_explain_context_title,
            R.string.error_explain_context_reason,
            ErrorAction.OpenLogs,
            detail,
        )

        text.containsAny(MODEL_HINTS) -> UserFacingError(
            R.string.error_explain_model_title,
            R.string.error_explain_model_reason,
            ErrorAction.OpenModelSettings,
            detail,
        )

        text.containsAny(SERVER_HINTS) -> UserFacingError(
            R.string.error_explain_server_title,
            R.string.error_explain_server_reason,
            ErrorAction.OpenLogs,
            detail,
        )

        // --- local failures ------------------------------------------------------------
        chain.any { it is SerializationException } -> UserFacingError(
            R.string.error_explain_format_title,
            R.string.error_explain_format_reason,
            ErrorAction.OpenLogs,
            detail,
        )

        text.contains("mcp") -> UserFacingError(
            R.string.error_explain_tool_title,
            R.string.error_explain_tool_reason,
            ErrorAction.OpenLogs,
            detail,
        )

        chain.any { it is SecurityException } -> UserFacingError(
            R.string.error_explain_permission_title,
            R.string.error_explain_permission_reason,
            ErrorAction.OpenLogs,
            detail,
        )

        chain.any { it is IOException } -> UserFacingError(
            R.string.error_explain_storage_title,
            R.string.error_explain_storage_reason,
            ErrorAction.OpenLogs,
            detail,
        )

        else -> UserFacingError(
            R.string.error_explain_unknown_title,
            R.string.error_explain_unknown_reason,
            ErrorAction.OpenLogs,
            detail,
        )
    }
}