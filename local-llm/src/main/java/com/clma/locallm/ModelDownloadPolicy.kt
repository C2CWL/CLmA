package com.clma.locallm

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest

/** Bounded retries and opt-in mirror selection adapted from moxw (AGPL-3.0, 81bbb7e). */
internal object ModelDownloadPolicy {
    const val MAX_ATTEMPTS = 4
    const val STALL_TIMEOUT_SECONDS = 45L
    private const val PRIMARY = "huggingface.co"
    private const val MIRROR = "hf-mirror.com"

    fun sources(url: String, mirrorEnabled: Boolean): List<String> {
        val parsed = url.toHttpUrlOrNull() ?: return listOf(url)
        val alternate = when (parsed.host) {
            PRIMARY -> MIRROR
            MIRROR -> PRIMARY
            else -> return listOf(url)
        }
        // Never forward credentials, signed query strings, or a custom port to another host.
        if (!mirrorEnabled || !parsed.isHttps || parsed.port != 443 || parsed.query != null ||
            parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            parsed.pathSegments.getOrNull(2) != "resolve"
        ) return listOf(url)
        return listOf(url, parsed.newBuilder().host(alternate).fragment(null).build().toString())
    }

    fun partialFile(target: File, url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // Legacy and import partials have no source identity; never resume from those.
        return File(target.parentFile, ".download-$hash-${target.name.hashCode().toUInt().toString(16)}.partial")
    }

    fun isTransientHttpCode(code: Int): Boolean = code in setOf(408, 425, 429) || code in 500..599
    fun backoffMillis(attempt: Int): Long = 1_000L shl (attempt - 2).coerceIn(0, 2)
}
