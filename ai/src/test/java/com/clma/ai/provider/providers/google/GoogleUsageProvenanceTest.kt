package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P2-11b: Gemini reports thoughts and cached content token counts on every usage snapshot. */
class GoogleUsageProvenanceTest {

    private fun parse(json: String): TokenUsage {
        val decoder = GoogleStreamDecoder("provenance-test", "gemini-test")
        val m = decoder.javaClass.getDeclaredMethod("parseUsage", JsonObject::class.java)
        m.isAccessible = true
        return m.invoke(decoder, Json.parseToJsonElement(json).jsonObject) as TokenUsage
    }

    @Test
    fun `thoughts and cached content keep their provenance`() {
        val usage = parse("""{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":30,"cachedContentTokenCount":40,"totalTokenCount":150}""")
        assertEquals(100, usage.promptTokens)
        assertEquals(50, usage.completionTokens) // candidates + thoughts, unchanged
        assertEquals(40, usage.cachedTokens)
        assertEquals(true, usage.cachedTokensReported)
        assertEquals(30, usage.reasoningTokens)

        val silent = parse("""{"promptTokenCount":100,"candidatesTokenCount":20,"totalTokenCount":120}""")
        assertEquals(0, silent.cachedTokens)
        assertEquals(false, silent.cachedTokensReported)
        assertNull(silent.reasoningTokens)
    }
}
