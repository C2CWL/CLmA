package com.clma.app.data.ai

import com.clma.common.android.Logging
import java.io.IOException
import java.lang.reflect.Proxy
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The interceptor runs on OkHttp dispatcher threads, where any non-IOException kills the
 * process, and it used to copy every request body (whole conversations) into memory.
 */
class RequestLoggingInterceptorTest {

    private val json = "application/json".toMediaType()

    @Before
    fun setUp() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)
    }

    @After
    fun tearDown() {
        Logging.setRequestLoggingEnabled(true)
        Logging.clear()
    }

    /** A body that fails the test if anyone tries to read it. */
    private class HugeBody(private val size: Long) : RequestBody() {
        var written = false
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = size
        override fun writeTo(sink: BufferedSink) {
            written = true
            throw AssertionError("oversized body must not be copied")
        }
    }

    private class UnknownLengthBody : RequestBody() {
        var written = false
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun writeTo(sink: BufferedSink) {
            written = true
            sink.writeUtf8("streamed")
        }
    }

    private fun post(body: RequestBody) = Request.Builder()
        .url("https://api.example.com/v1/chat/completions")
        .post(body)
        .build()

    /** Minimal Interceptor.Chain: only request() and proceed() are used by the interceptor. */
    private fun chain(request: Request, proceed: (Request) -> Response): Interceptor.Chain =
        Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java),
        ) { _, method, args ->
            when (method.name) {
                "request" -> request
                "proceed" -> proceed(args!![0] as Request)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Interceptor.Chain

    private fun ok(request: Request) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("{}".toResponseBody(json))
        .build()

    @Test
    fun `small body is captured`() {
        val request = post("""{"hello":"world"}""".toRequestBody(json))
        assertEquals("""{"hello":"world"}""", RequestLoggingInterceptor.captureRequestBody(request))
    }

    @Test
    fun `oversized body is not copied and a placeholder is stored`() {
        val body = HugeBody(10L * 1024 * 1024)
        val request = post(body)

        val response = RequestLoggingInterceptor().intercept(chain(request, ::ok))

        assertEquals(200, response.code)
        assertFalse(body.written)
        val logged = Logging.getRequestLogs().single()
        assertEquals(Logging.omittedBodyPlaceholder(10L * 1024 * 1024), logged.requestBody)
    }

    @Test
    fun `body of unknown length is not read`() {
        val body = UnknownLengthBody()
        assertNull(RequestLoggingInterceptor.captureRequestBody(post(body)))
        assertFalse(body.written)
    }

    @Test
    fun `successful call is not recorded while recording is off`() {
        Logging.setRequestLoggingEnabled(false)
        val request = post("""{"a":1}""".toRequestBody(json))

        RequestLoggingInterceptor().intercept(chain(request, ::ok))

        assertTrue(Logging.getRequestLogs().isEmpty())
    }

    @Test
    fun `runtime exception from proceed surfaces as IOException`() {
        val request = post("""{"a":1}""".toRequestBody(json))
        val cause = IllegalStateException("bug further down the chain")
        try {
            RequestLoggingInterceptor().intercept(chain(request) { throw cause })
            fail("expected IOException")
        } catch (e: IOException) {
            assertSame(cause, e.cause)
        }
        // The failure is still recorded (forced), with the error message.
        val logged = Logging.getRequestLogs().single()
        assertEquals("bug further down the chain", logged.error)
    }

    @Test
    fun `IOException from proceed passes through unchanged`() {
        val request = post("""{"a":1}""".toRequestBody(json))
        val original = IOException("Canceled")
        try {
            RequestLoggingInterceptor().intercept(chain(request) { throw original })
            fail("expected IOException")
        } catch (e: IOException) {
            assertSame(original, e)
        }
    }
}
