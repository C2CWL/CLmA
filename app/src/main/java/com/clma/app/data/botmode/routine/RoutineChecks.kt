// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/routines.ts, server/routine-run-gate.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.routine

import com.clma.ai.util.HttpException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

internal object RoutineChecks {
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private const val MAX_BYTES = 2 * 1024 * 1024

    suspend fun pageHash(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url.toHttpUrl()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(
                "HTTP ${response.code}", response.code,
                response.header("Retry-After"), response.header("x-ratelimit-reset"),
            )
            val source = response.body.source()
            val digest = MessageDigest.getInstance("SHA-256")
            var remaining = MAX_BYTES
            while (remaining > 0 && !source.exhausted()) {
                val chunk = source.readByteArray(minOf(source.buffer.size, remaining.toLong()))
                digest.update(chunk)
                remaining -= chunk.size
            }
            check(source.exhausted()) { "Page exceeds the check size limit" }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private val quotaMessage = Regex("\\b429\\b|quota|rate.limit|usage.limit|resource.exhausted", RegexOption.IGNORE_CASE)
    private val resetField = Regex(
        """(?i)["']?(?:resets_at|reset_at|retry_after|retryDelay|resets_in_seconds)["']?\s*[:=]\s*["']?([0-9][0-9A-Za-z:.+\-]*)""",
    )

    fun quotaFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        (it as? HttpException)?.statusCode == 429 || quotaMessage.containsMatchIn(it.message.orEmpty())
    }

    fun resetAt(error: Throwable, now: Long): Long? = generateSequence(error) { it.cause }.firstNotNullOfOrNull {
        val http = it as? HttpException
        val reset = resetField.find(http?.responseDetail ?: it.message.orEmpty())?.groupValues?.get(1)
        quotaRetryAt(now, http?.retryAfter, http?.rateLimitReset ?: reset)
    }
}
