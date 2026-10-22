package com.clma.ai.util

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JsonRequestBodyTest {
    private val tree = buildJsonObject {
        put("model", "deepseek-v4-flash")
        put("stream", true)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                // CJK, escapes and an emoji (surrogate pair) must come out byte-identical.
                put("content", "你好 \"quoted\" \\ back\nslash 😀 " + "x".repeat(10_000))
            })
        })
    }

    @Test
    fun `streams exactly the bytes encodeToString produced`() {
        val expected = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), tree)
            .encodeToByteArray()
        val buffer = Buffer()
        JsonRequestBody(tree).writeTo(buffer)
        assertArrayEquals(expected, buffer.readByteArray())
    }

    @Test
    fun `content length is exact so no chunked upload is needed`() {
        val body = JsonRequestBody(tree)
        val buffer = Buffer()
        body.writeTo(buffer)
        assertEquals(buffer.size, body.contentLength())
    }

    @Test
    fun `body is repeatable for OkHttp retries and the request logger`() {
        val body = tree.toJsonRequestBody()
        val first = Buffer().also(body::writeTo).readByteArray()
        val second = Buffer().also(body::writeTo).readByteArray()
        assertArrayEquals(first, second)
        assertFalse(body.isOneShot())
        assertEquals("application/json", body.contentType().toString())
    }
}
