package com.clma.ai.util

import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * A JSON request body that is serialized straight into OkHttp's socket sink.
 *
 * `json.encodeToString(body).toRequestBody()` used to hold the whole request three times at
 * once: kotlinx's growing `CharArray` (up to twice the body), the resulting String (UTF-16 for
 * CJK text), and the UTF-8 `ByteArray` OkHttp sends. For a conversation with many images or a
 * large document that is several hundred MB on a 512 MB heap - the "Failed to allocate a
 * 88571288 byte allocation" crash. Streaming keeps only the JSON tree plus a small buffer.
 *
 * - **Repeatable:** [writeTo] re-encodes [element] each time, so OkHttp retries/redirects and
 *   the request logger's (size-capped) capture work as before.
 * - **Content-Length:** computed once by encoding into a byte-counting stream that keeps
 *   nothing, so the request still carries an exact `Content-Length` (no chunked upload, which
 *   some OpenAI-compatible gateways reject). Costs one extra serialization pass of CPU, no memory.
 */
@OptIn(ExperimentalSerializationApi::class)
class JsonRequestBody(
    val element: JsonElement,
    private val format: Json = json,
) : RequestBody() {
    private val length: Long by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val counter = CountingOutputStream()
        format.encodeToStream(JsonElement.serializer(), element, counter)
        counter.count
    }

    override fun contentType(): MediaType = JSON_MEDIA_TYPE

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        // Not closed: the sink belongs to OkHttp. encodeToStream flushes its own buffer.
        format.encodeToStream(JsonElement.serializer(), element, sink.outputStream())
    }

    private class CountingOutputStream : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
        }
    }

    companion object {
        val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()
    }
}

/** Streams this JSON tree as a request body; see [JsonRequestBody]. */
fun JsonElement.toJsonRequestBody(): RequestBody = JsonRequestBody(this)
