package com.clma.ai.provider.providers.openai

import com.clma.ai.core.TokenUsage
import com.clma.ai.provider.PromptCacheDiagnosticPurpose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheDiagnosticsTest {
    private val tracker = PromptCachePrefixTracker()
    private val initial = """{"model":"test","messages":[{"role":"system","content":"private text"}]}"""

    private fun record(
        body: String = initial,
        session: String = "one",
        provider: String = "p",
        endpoint: String = "e",
        purpose: PromptCacheDiagnosticPurpose = PromptCacheDiagnosticPurpose.CHAT,
    ) = tracker.record(session, provider, endpoint, Json.parseToJsonElement(body).jsonObject, purpose)

    @Test
    fun `distinguishes append from early rewrite without logging message contents`() {
        val first = record()
        val appended = record(initial.replace("]}", """,{"role":"user","content":"new"}]}"""))
        assertEquals(first.series, appended.series)
        assertTrue(appended.fields.contains("previous=${first.id} "))
        assertTrue(appended.fields.contains("prefixMessages=1"))
        assertTrue(appended.fields.contains("firstChangedMessage=1 change=append"))
        val rewritten = record(initial.replace("private text", "replacement"))
        assertTrue(rewritten.fields.contains("firstChangedMessage=0 change=rewrite"))
        assertFalse(rewritten.fields.contains("private text"))
        assertFalse(rewritten.fields.contains("replacement"))
    }

    @Test
    fun `compares tools and cache routing options independently from messages`() {
        record()
        val next = record(initial.dropLast(1) + ""","tools":[],"prompt_cache_key":"private key"}""")
        assertTrue(next.fields.contains("toolsChanged=true optionsChanged=true"))
        assertTrue(next.fields.contains("firstChangedMessage=none change=identical"))
        assertFalse(next.fields.contains("private key"))
    }

    @Test
    fun `does not compare across conversations providers endpoints or wire models`() {
        record()
        listOf(
            record(session = "two"),
            record(provider = "other"),
            record(endpoint = "other"),
            record(initial.replace("\"test\"", "\"other\"")),
        ).forEach { assertTrue(it.fields.contains("previous=none")) }
    }

    @Test
    fun `interleaved compaction does not replace the previous chat snapshot`() {
        val chat = record()
        val summaryBody = """{"model":"test","messages":[{"role":"user","content":"summarize"}]}"""
        val compact = record(summaryBody, purpose = PromptCacheDiagnosticPurpose.COMPACTION)
        val appended = record(initial.replace("]}", """,{"role":"user","content":"next"}]}"""))
        val compactAgain = record(summaryBody, purpose = PromptCacheDiagnosticPurpose.COMPACTION)
        val unspecified = record(purpose = PromptCacheDiagnosticPurpose.UNSPECIFIED)

        assertFalse(chat.series == compact.series)
        assertTrue(compact.fields.contains("previous=none "))
        assertEquals(chat.series, appended.series)
        assertTrue(appended.fields.contains("previous=${chat.id} "))
        assertTrue(appended.fields.contains("firstChangedMessage=1 change=append"))
        assertEquals(compact.series, compactAgain.series)
        assertTrue(compactAgain.fields.contains("previous=${compact.id} "))
        assertTrue(compactAgain.fields.contains("firstChangedMessage=none change=identical"))
        assertTrue(unspecified.fields.contains("previous=none "))
    }

    @Test
    fun `does not confuse zero cache hits with unreported usage`() {
        val usage = TokenUsage(promptTokens = 10, completionTokens = 0, totalTokens = 10, cachedTokens = 0)
        assertTrue(formatCacheUsage(usage.copy(cachedTokensReported = false)).contains("cacheHit=unreported"))
        assertTrue(formatCacheUsage(usage.copy(cachedTokensReported = true)).contains("cacheHit=0 "))
        assertEquals("usage=unreported", formatCacheUsage(null))
    }
}
