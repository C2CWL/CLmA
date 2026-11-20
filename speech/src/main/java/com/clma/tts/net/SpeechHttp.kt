package com.clma.tts.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * HTTP client for every TTS provider.
 *
 * :speech sits below :app and cannot read [com.clma.app.data.datastore.SettingsStore], so the
 * app process installs its proxy-aware client into [base] at startup (see ClmaApp). Each call
 * derives a per-provider read timeout from that client, so provider requests follow the same
 * app proxy, credentials and connection pool as the rest of the app instead of opening a
 * direct connection of their own. Library/test callers that never install one keep the old
 * direct behaviour rather than failing on a missing graph.
 */
object SpeechHttp {
    @Volatile
    var base: OkHttpClient? = null

    fun client(readTimeoutSeconds: Long): OkHttpClient {
        val builder = base?.newBuilder()
            ?: OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        return builder.readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
    }
}