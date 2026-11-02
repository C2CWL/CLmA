package com.clma.app.connectors

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request

class ConnectorException(val code: String) : Exception(code)

/** Retains the configured proxy/TLS transport, but never request/AI logging interceptors. */
class ConnectorHttp(shared: OkHttpClient) {
    private val client = shared.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.eventListener(okhttp3.EventListener.NONE)
        .authenticator(okhttp3.Authenticator.NONE)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun json(request: Request): JsonElement = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ConnectorException("connector_http_${response.code}")
                val source = response.body.source()
                source.request(MAX_BYTES + 1)
                if (source.buffer.size > MAX_BYTES) throw ConnectorException("connector_response_too_large")
                Json.parseToJsonElement(source.readUtf8())
            }
        } catch (e: IOException) {
            throw ConnectorException("connector_network_error")
        } catch (e: IllegalArgumentException) {
            throw ConnectorException("connector_invalid_response")
        }
    }

    private companion object {
        const val MAX_BYTES = 1_048_576L
    }
}
