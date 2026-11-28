package com.clma.ai.util

import okhttp3.Response

/** Keep transport details alongside the provider's existing user-facing error. */
class HttpFailureMetadata(
    val status: Int,
    val retryAfter: String?,
    val rateLimitResets: List<String>,
    val errorBody: String? = null,
    val outputStarted: Boolean = false,
) : Exception(null, null, false, false)

fun Throwable?.withHttpFailureMetadata(
    response: Response?,
    errorBody: String? = null,
    enabled: Boolean = true,
    outputStarted: Boolean = false,
): Throwable? {
    if (!enabled || response == null || response.isSuccessful) return this
    val resetHeaders = response.headers.filter { (name, _) ->
        name.equals("x-ratelimit-reset", true) ||
            name.startsWith("x-ratelimit-reset-", true) ||
            name.equals("anthropic-ratelimit-unified-reset", true)
    }.filter { (name, _) ->
        val remainingHeader = name.replace("reset", "remaining", ignoreCase = true)
        response.header(remainingHeader)?.toDoubleOrNull()?.let { it <= 0 } ?: true
    }.map { it.second }
    val retryAfter = response.header("Retry-After")
    val failure = this ?: if (response.code == 429 || retryAfter != null || resetHeaders.isNotEmpty()) {
        HttpException("HTTP ${response.code}", response.code)
    } else {
        return null
    }
    failure.addSuppressed(HttpFailureMetadata(response.code, retryAfter, resetHeaders, errorBody?.take(8_192), outputStarted))
    return failure
}
