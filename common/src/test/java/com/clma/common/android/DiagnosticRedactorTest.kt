package com.clma.common.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticRedactorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `all non-allowlisted header values are hidden regardless of casing`() {
        val headers = DiagnosticRedactor.headers(mapOf(
            "Authorization" to "Bearer tiny",
            "X-Custom-Secret" to "private",
            "Set-Cookie" to "session=private",
            "Content-Type" to "application/json",
        ))
        assertEquals("application/json", headers["Content-Type"])
        assertTrue(headers.filterKeys { it != "Content-Type" }.values.all { it == "[REDACTED]" })
    }

    @Test
    fun `url removes user info fragment and every query value including encoded names`() {
        val value = DiagnosticRedactor.url("https://alice:pass@example.com/v1?api%5Fkey=hidden&custom=private#token")
        listOf("alice", "pass", "hidden", "private", "#token").forEach { assertFalse(value.contains(it)) }
        assertTrue(value.contains("example.com/v1"))
        assertEquals("[URL omitted]", DiagnosticRedactor.url("not a URL"))
    }

    @Test
    fun `nested JSON credentials and private fields are removed before previews`() {
        val value = DiagnosticRedactor.body("""{"api_key":"hidden","nested":[{"Password":"private","email":"pii@example.com","ok":2}],"text":"Bearer abc"}""")
        listOf("hidden", "private", "pii@example.com", "Bearer abc").forEach { assertFalse(value.contains(it)) }
        assertTrue(value.contains("\"ok\",2"))
        assertEquals("[Non-JSON body omitted]", DiagnosticRedactor.body("{\"api_key\",\"truncated"))
        assertEquals("[Non-JSON body omitted]", DiagnosticRedactor.body("password=plaintext"))
    }

    @Test
    fun `text redaction covers quoted assignments short bearer and key patterns`() {
        val value = DiagnosticRedactor.text("""password="two words" Authorization: Bearer abc sk-1234567890 token=xyz""")
        listOf("two words", "Bearer abc", "sk-1234567890", "xyz").forEach { assertFalse(value.contains(it)) }
    }

    @Test
    fun `memory disk and exported logs use the same redacted boundary`() {
        val dir = temporaryFolder.newFolder()
        try {
            Logging.init(dir)
            Logging.logRequest(LogEntry.RequestLog(
                tag = "HTTP",
                method = "POST",
                url = "https://example.com?key=url-secret",
                requestHeaders = mapOf("x-api-key" to "header-secret"),
                responseHeaders = mapOf("Set-Cookie" to "cookie-secret"),
                requestBody = """{"password":"body-secret"}""",
                error = "Bearer error-secret",
            ), force = true)
            Logging.log("Test", "password=text-secret")
            val memory = Logging.getRecentLogs().toString()
            val disk = File(dir, "app.log").readText()
            val exported = Logging.exportAsText()
            listOf(memory, disk, exported).forEach { output ->
                listOf("url-secret", "header-secret", "cookie-secret", "body-secret", "error-secret", "text-secret")
                    .forEach { assertFalse(output.contains(it)) }
            }
            assertFalse(disk.contains("requestBody"))
        } finally {
            Logging.clear()
            Logging.resetFileLogForTest()
        }
    }
}
