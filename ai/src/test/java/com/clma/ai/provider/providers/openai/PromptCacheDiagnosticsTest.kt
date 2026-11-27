package com.clma.ai.provider.providers.openai

import com.clma.ai.core.TokenUsage
import com.clma.ai.provider.CONTEXT_BUDGET_REMINDER_PREFIX
import com.clma.ai.provider.PromptCacheDiagnosticPurpose
import com.clma.ai.provider.PromptCacheSystemSection
import com.clma.ai.provider.TOOL_OUTPUT_PROJECTION_MARKER
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
        systemSections: Map<PromptCacheSystemSection, String> = emptyMap(),
    ) = tracker.record(session, provider, endpoint, Json.parseToJsonElement(body).jsonObject, purpose, systemSections)

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
    fun `names changed system inputs without logging their text`() {
        val sections = mapOf(
            PromptCacheSystemSection.ASSISTANT to "assistant instructions",
            PromptCacheSystemSection.RECENT_CHATS to "private title before",
        )
        record(systemSections = sections)
        val next = record(
            initial.replace("private text", "updated system text"),
            systemSections = sections + (PromptCacheSystemSection.RECENT_CHATS to "private title after"),
        )
        assertTrue(next.fields.contains("oldRole=system newRole=system systemInputsChanged=recent_chats"))
        assertFalse(next.fields.contains("private title"))
        assertFalse(next.fields.contains("updated system text"))
    }

    @Test
    fun `recognizes a projection transition only for the same tool call`() {
        fun body(id: String, content: String) =
            """{"model":"test","messages":[{"role":"tool","tool_call_id":"$id","content":"$content"}]}"""
        val marker = "$TOOL_OUTPUT_PROJECTION_MARKER Older result (~700 tokens). Private file reference."
        record(body("private id", "private tool output"))
        val projected = record(body("private id", marker))
        assertTrue(projected.fields.contains("oldRole=tool newRole=tool toolProjection=added"))
        assertFalse(projected.fields.contains("private id"))
        assertFalse(projected.fields.contains("Private file reference"))
        val restored = record(body("private id", "private tool output"))
        assertTrue(restored.fields.contains("toolProjection=removed"))
        val differentCall = record(body("different id", marker))
        assertFalse(differentCall.fields.contains("toolProjection="))
    }

    @Test
    fun `labels the changing reminder position without logging reminder text`() {
        fun reminder(value: String) =
            """{"role":"user","content":"$CONTEXT_BUDGET_REMINDER_PREFIX$value</context_reminder>"}"""
        fun body(vararg messages: String) = initial.replace("]}", "," + messages.joinToString(",") + "]}")
        val user = """{"role":"user","content":"question"}"""
        val tool = """{"role":"tool","content":"result"}"""
        record(body(user, reminder("private old estimate")))
        val changed = record(body(user, reminder("private new estimate")))
        assertTrue(changed.fields.contains("firstChangedMessage=2 change=rewrite"))
        assertTrue(changed.fields.contains("contextBudgetReminderAtChange=both"))
        assertFalse(changed.fields.contains("private"))
        val afterTool = record(body(user, tool, reminder("private next estimate")))
        assertTrue(afterTool.fields.contains("firstChangedMessage=2 change=rewrite"))
        assertTrue(afterTool.fields.contains("contextBudgetReminderAtChange=old"))
        val repeated = record(body(user, tool, reminder("private next estimate")))
        assertFalse(repeated.fields.contains("contextBudgetReminderAtChange="))
    }

    @Test
    fun `does not confuse zero cache hits with unreported usage`() {
        val usage = TokenUsage(promptTokens = 10, completionTokens = 0, totalTokens = 10, cachedTokens = 0)
        assertTrue(formatCacheUsage(usage.copy(cachedTokensReported = false)).contains("cacheHit=unreported"))
        assertTrue(formatCacheUsage(usage.copy(cachedTokensReported = true)).contains("cacheHit=0 "))
        assertEquals("usage=unreported", formatCacheUsage(null))
    }
}
