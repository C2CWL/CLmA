package com.clma.app.connectors

import java.nio.charset.Charset
import java.util.Base64
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

object GmailMime {
    fun header(payload: JsonObject, name: String): String? =
        (payload["headers"] as? JsonArray)?.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.content?.equals(name, ignoreCase = true) == true
        }?.jsonObject?.get("value")?.jsonPrimitive?.content

    fun message(message: JsonObject): JsonObject {
        val payload = message["payload"]?.jsonObject ?: JsonObject(emptyMap())
        val pending = ArrayDeque<JsonObject>().apply { add(payload) }
        val plain = StringBuilder()
        val html = StringBuilder()
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            // Attachments, including attached message/rfc822 parts, are not email body text.
            if (!part["filename"]?.jsonPrimitive?.content.orEmpty().isBlank()) continue
            (part["parts"] as? JsonArray)?.forEach { pending.add(it.jsonObject) }
            val mime = part["mimeType"]?.jsonPrimitive?.content.orEmpty()
            if (mime != "text/plain" && mime != "text/html") continue
            val data = part["body"]?.jsonObject?.get("data")?.jsonPrimitive?.content ?: continue
            val charsetName = header(part, "Content-Type")?.let {
                Regex("charset\\s*=\\s*\"?([^;\\s\"]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
            }
            val charset = charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            val decoded = String(Base64.getUrlDecoder().decode(data), charset)
            val target = if (mime == "text/plain") plain else html
            if (target.length < MAX_TEXT) {
                val text = if (mime == "text/html") Jsoup.parse(decoded).text() else decoded
                target.append(text.take(MAX_TEXT - target.length)).append('\n')
            }
        }
        return buildJsonObject {
            put("untrusted_content", true)
            listOf("id", "threadId", "snippet", "internalDate", "labelIds").forEach { key ->
                message[key]?.let { put(key, it) }
            }
            listOf("From", "To", "Cc", "Reply-To", "Subject", "Date").forEach { key ->
                header(payload, key)?.let { put(key.lowercase(), it.take(2000)) }
            }
            put("body", (plain.toString().ifBlank { html.toString() }).take(MAX_TEXT))
            put("body_may_be_truncated", plain.length >= MAX_TEXT || html.length >= MAX_TEXT)
            put("attachments_included", false)
        }
    }

    fun validate(draft: MailDraft): MailDraft {
        require(draft.to.length <= 2000 && draft.cc.length <= 2000 && draft.subject.length <= 998)
        require(draft.body.length <= 50_000)
        listOf(draft.to, draft.cc, draft.subject, draft.inReplyTo.orEmpty(), draft.references.orEmpty()).forEach {
            require('\r' !in it && '\n' !in it && '\u0000' !in it) { "mail_header_injection" }
        }
        val address = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
        require(draft.to.split(',').all { it.trim().length <= 254 && address.matches(it.trim()) }) { "invalid_mail_recipient" }
        require(draft.cc.isBlank() || draft.cc.split(',').all { it.trim().length <= 254 && address.matches(it.trim()) }) { "invalid_mail_recipient" }
        return draft
    }

    fun raw(draft: MailDraft): String {
        validate(draft)
        val encodedSubject = encodeSubject(draft.subject)
        val encodedBody = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(draft.body.toByteArray(Charsets.UTF_8))
        val message = buildString {
            append("To: " + draft.to.split(',').joinToString(",\r\n ") { it.trim() } + "\r\n")
            if (draft.cc.isNotBlank()) append("Cc: " + draft.cc.split(',').joinToString(",\r\n ") { it.trim() } + "\r\n")
            append("Subject: $encodedSubject\r\n")
            draft.inReplyTo?.let { append("In-Reply-To: $it\r\n") }
            draft.references?.let { append("References: $it\r\n") }
            append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n$encodedBody\r\n")
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(message.toByteArray(Charsets.UTF_8))
    }

    // RFC 2047 limits each encoded-word to 75 bytes; never split a UTF-8 code point.
    private fun encodeSubject(subject: String): String {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var bytes = 0
        subject.codePoints().forEach { codePoint ->
            val text = String(Character.toChars(codePoint))
            val size = text.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 42) {
                chunks += current.toString()
                current.clear()
                bytes = 0
            }
            current.append(text)
            bytes += size
        }
        chunks += current.toString()
        return chunks.joinToString("\r\n ") {
            "=?UTF-8?B?" + Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) + "?="
        }
    }

    private const val MAX_TEXT = 20_000
}
