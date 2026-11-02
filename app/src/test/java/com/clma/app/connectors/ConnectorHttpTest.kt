package com.clma.app.connectors

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ConnectorHttpTest {
    private lateinit var server: HttpServer
    private lateinit var origin: String

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        origin = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `shared loggers listeners and cookies never observe connector traffic`() = runBlocking {
        val observations = AtomicInteger()
        var cookieHeader: String? = null
        server.createContext("/data") { exchange ->
            cookieHeader = exchange.requestHeaders.getFirst("Cookie")
            respond(exchange, 200, """{"ok":true}""")
        }
        val shared = OkHttpClient.Builder()
            .addInterceptor { chain -> observations.incrementAndGet(); chain.proceed(chain.request()) }
            .addNetworkInterceptor { chain -> observations.incrementAndGet(); chain.proceed(chain.request()) }
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { observations.incrementAndGet() }
            })
            .cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    observations.incrementAndGet()
                    return listOf(Cookie.Builder().name("session").value("test-only").hostOnlyDomain(url.host).build())
                }
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { observations.incrementAndGet() }
            }).build()
        val response = ConnectorHttp(shared).json(Request.Builder().url("$origin/data").build())
        assertEquals("true", response.jsonObject.getValue("ok").jsonPrimitive.content)
        assertEquals(0, observations.get())
        assertNull(cookieHeader)
    }

    @Test
    fun `responses are bounded at one MiB without truncating JSON`() = runBlocking {
        val limit = 1_048_576
        server.createContext("/maximum") { respond(it, 200, "\"" + "x".repeat(limit - 2) + "\"") }
        server.createContext("/oversize") { respond(it, 200, "\"" + "x".repeat(limit - 1) + "\"") }
        val http = ConnectorHttp(OkHttpClient())
        val response = http.json(Request.Builder().url("$origin/maximum").build())
        assertEquals(limit - 2, response.jsonPrimitive.content.length)
        try {
            http.json(Request.Builder().url("$origin/oversize").build())
            throw AssertionError("An oversized response was accepted")
        } catch (error: ConnectorException) {
            assertEquals("connector_response_too_large", error.code)
        }
    }

    @Test
    fun `redirects and shared authenticators cannot replay an external write`() = runBlocking {
        val destinationCalls = AtomicInteger()
        val authenticatorCalls = AtomicInteger()
        server.createContext("/redirect") {
            it.responseHeaders.add("Location", "$origin/destination")
            respond(it, 307, "")
        }
        server.createContext("/destination") { destinationCalls.incrementAndGet(); respond(it, 200, "{}") }
        server.createContext("/unauthorized") {
            it.responseHeaders.add("WWW-Authenticate", "Basic realm=connector-test")
            respond(it, 401, "sensitive provider body must not escape")
        }
        val http = ConnectorHttp(OkHttpClient.Builder().authenticator { _, _ ->
            authenticatorCalls.incrementAndGet()
            null
        }.build())
        listOf("redirect" to "connector_http_307", "unauthorized" to "connector_http_401").forEach { (path, code) ->
            try {
                http.json(Request.Builder().url("$origin/$path").build())
                throw AssertionError("An HTTP error was accepted")
            } catch (error: ConnectorException) {
                assertEquals(code, error.code)
                assertEquals(code, error.message)
            }
        }
        assertEquals(0, destinationCalls.get())
        assertEquals(0, authenticatorCalls.get())
    }

    private fun respond(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
