package com.clma.app.connectors

import java.nio.charset.Charset
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailMimeTest {
    private val draft = MailDraft(
        id = "local-test", account = "alice@example.com", to = "bob@example.com",
        cc = "carol@example.com", subject = "测试 😀", body = "First line\r\n第二行 😀",
    )

    private fun part(mime: String, text: String, filename: String = "", charset: Charset = Charsets.UTF_8): JsonObject = buildJsonObject {
        put("mimeType", mime)
        put("filename", filename)
        put("headers", JsonArray(listOf(buildJsonObject {
            put("name", "cOnTeNt-TyPe"); put("value", "$mime; charset=\"${charset.name()}\"")
        })))
        put("body", buildJsonObject { put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(charset))) })
    }

    private fun decoded(payload: JsonObject): JsonObject = GmailMime.message(buildJsonObject {
        put("id", "message-id"); put("payload", payload)
    })

    @Test
    fun `multipart prefers plain text and excludes nested attachments`() {
        val payload = buildJsonObject {
            put("mimeType", "multipart/mixed")
            put("parts", JsonArray(listOf(
                part("text/html", "<p>HTML alternative</p>"),
                part("text/plain", "Plain body"),
                part("text/plain", "Attachment secrets", "notes.txt"),
                buildJsonObject {
                    put("mimeType", "message/rfc822"); put("filename", "forwarded.eml")
                    put("parts", JsonArray(listOf(part("text/plain", "Attached mail"))))
                },
            )))
        }
        val result = decoded(payload)
        assertEquals("Plain body\n", result["body"]!!.jsonPrimitive.content)
        assertTrue(result["untrusted_content"]!!.jsonPrimitive.boolean)
        assertFalse(result["attachments_included"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `HTML only mail becomes plain text without script instructions`() {
        val result = decoded(part("text/html", "<p>Hello <b>world</b></p><script>steal_token()</script>"))
        assertEquals("Hello world\n", result["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `MIME charset and case insensitive headers are honored`() {
        val payload = part("text/plain", "café", charset = Charsets.ISO_8859_1)
        assertEquals("text/plain; charset=\"ISO-8859-1\"", GmailMime.header(payload, "content-type"))
        assertEquals("café\n", decoded(payload)["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `body output is bounded and truncation is explicit`() {
        val result = decoded(part("text/plain", "x".repeat(25_000)))
        assertEquals(20_000, result["body"]!!.jsonPrimitive.content.length)
        assertTrue(result["body_may_be_truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `outgoing plain text MIME round trips Unicode and reply headers`() {
        val reply = draft.copy(inReplyTo = "<original@example.com>", references = "<original@example.com>")
        val encoded = GmailMime.raw(reply)
        assertFalse(encoded.contains('='))
        val raw = String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        assertTrue(raw.contains("To: bob@example.com\r\nCc: carol@example.com\r\n"))
        assertTrue(raw.contains("In-Reply-To: <original@example.com>\r\n"))
        assertTrue(raw.contains("Content-Type: text/plain; charset=UTF-8\r\n"))
        val body = Base64.getMimeDecoder().decode(raw.substringAfter("\r\n\r\n"))
        assertEquals(reply.body, String(body, Charsets.UTF_8))
    }

    @Test
    fun `long Unicode subjects use legal encoded words without splitting code points`() {
        val subject = "尘😀abc".repeat(120)
        val raw = String(Base64.getUrlDecoder().decode(GmailMime.raw(draft.copy(subject = subject))), Charsets.UTF_8)
        val words = Regex("=\\?UTF-8\\?B\\?([^?]*)\\?=").findAll(raw.substringBefore("\r\n\r\n")).toList()
        assertTrue(words.size > 1)
        assertTrue(words.all { it.value.length <= 75 })
        assertEquals(subject, words.joinToString("") { String(Base64.getDecoder().decode(it.groupValues[1]), Charsets.UTF_8) })
        assertTrue(raw.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 998 })
    }

    @Test
    fun `header injection and non bare recipients are rejected before encoding`() {
        listOf(
            draft.copy(to = "bob@example.com\r\nBcc: eve@example.com"),
            draft.copy(cc = "carol@example.com\nBcc: eve@example.com"),
            draft.copy(subject = "Safe\r\nBcc: eve@example.com"),
            draft.copy(inReplyTo = "<id@example.com>\r\nX-Evil: yes"),
            draft.copy(references = "<id@example.com>\u0000"),
            draft.copy(to = "Bob <bob@example.com>"),
            draft.copy(to = ""),
            draft.copy(body = "x".repeat(50_001)),
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { GmailMime.raw(invalid) } }
    }
}
