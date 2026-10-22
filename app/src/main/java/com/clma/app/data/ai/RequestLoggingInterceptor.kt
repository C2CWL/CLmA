package com.clma.app.data.ai

import com.clma.common.android.LogEntry
import com.clma.common.android.LogLevel
import com.clma.common.android.Logging
import com.clma.ai.util.RequestTooLargeException
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * Records every HTTP call for the Logs screen.
 *
 * This runs on OkHttp's dispatcher threads. For an enqueued call (every SSE stream) OkHttp
 * rethrows anything that is not an [IOException] from there, which kills the whole process.
 * So nothing in here may throw anything but [IOException]: body capture is bounded and
 * best-effort, and a non-IO failure from the rest of the chain is surfaced as an IOException
 * (the original kept as the cause) so it reaches the caller as an ordinary request failure.
 */
class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        // Even with request logging off we still capture the request when it fails: the whole
        // point of opening the Logs screen is usually "this call broke", and a silent switch
        // means the evidence is gone by the time the user looks.
        val loggingEnabled = runCatching { Logging.isRequestLoggingEnabled() }.getOrDefault(false)
        val requestHeaders = runCatching { request.headers.toMap() }.getOrDefault(emptyMap())
        // Bodies carry prompts and tool output; only keep them when the user opted in.
        val requestBody = if (loggingEnabled) captureRequestBody(request) else null

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Throwable) {
            safeLog {
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = e.message ?: e.javaClass.name,
                    level = LogLevel.ERROR,
                ) to true
            }
            throw asIOException(e)
        }

        val durationMs = System.currentTimeMillis() - startTime
        val failed = response.code >= 400
        safeLog {
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.toString(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = response.headers.toMap(),
                durationMs = durationMs,
                level = if (failed) LogLevel.ERROR else LogLevel.INFO,
            ) to failed
        }

        return response
    }

    private inline fun safeLog(build: () -> Pair<LogEntry.RequestLog, Boolean>) {
        try {
            val (entry, force) = build()
            Logging.logRequest(entry, force = force)
        } catch (_: Throwable) {
            // Logging is diagnostics only; it must never fail the request or the process.
        }
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { name ->
            if (name.equals("Proxy-Authorization", ignoreCase = true) ||
                name.equals("Authorization", ignoreCase = true) ||
                name.equals("x-api-key", ignoreCase = true) ||
                name.equals("api-key", ignoreCase = true)
            ) {
                "██"
            } else {
                get(name) ?: ""
            }
        }
    }

    companion object {
        /** Bodies above this size (bytes) are not copied; a placeholder is logged instead. */
        const val MAX_CAPTURED_BODY_BYTES = 32L * 1024

        /**
         * Copies the request body for the log only when its size is known and small. Never
         * throws: an unknown length, a one-shot/duplex body, or any failure yields null or a
         * placeholder.
         */
        internal fun captureRequestBody(request: Request): String? = try {
            val body = request.body
            when {
                body == null -> null
                body.isDuplex() || body.isOneShot() -> null
                else -> {
                    val length = body.contentLength()
                    when {
                        length < 0 -> null
                        length > MAX_CAPTURED_BODY_BYTES -> Logging.omittedBodyPlaceholder(length)
                        else -> Buffer().also { body.writeTo(it) }.readUtf8()
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }

        /**
         * IOExceptions (including OkHttp's cancellation, which is an IOException "Canceled")
         * pass through untouched; anything else is wrapped so OkHttp reports it to the
         * callback instead of rethrowing it on the dispatcher thread.
         */
        internal fun asIOException(e: Throwable): IOException = when (e) {
            is IOException -> e
            // Still an IOException (so OkHttp's dispatcher thread never rethrows it and kills
            // the process), but a marked one: the retry policy refuses RequestTooLargeException,
            // because rebuilding the same request runs out of memory again.
            is OutOfMemoryError -> RequestTooLargeException("${e.javaClass.name}: ${e.message}", e)
            else -> IOException("${e.javaClass.name}: ${e.message}", e)
        }
    }
}
