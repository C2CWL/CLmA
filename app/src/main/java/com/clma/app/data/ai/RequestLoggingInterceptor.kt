package com.clma.app.data.ai

import com.clma.common.android.LogEntry
import com.clma.common.android.LogLevel
import com.clma.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // Even with request logging off we still capture the request when it fails: the whole
        // point of opening the Logs screen is usually "this call broke", and a silent switch
        // means the evidence is gone by the time the user looks.
        val loggingEnabled = Logging.isRequestLoggingEnabled()

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toMap()
        // Bodies carry prompts and tool output; only keep them when the user opted in.
        val requestBody = if (loggingEnabled) request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        } else null

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error,
                    level = LogLevel.ERROR,
                ),
                force = true,
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap()

        val failed = response.code >= 400
        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.toString(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error,
                level = if (failed) LogLevel.ERROR else LogLevel.INFO,
            ),
            force = failed,
        )

        return response
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
}
