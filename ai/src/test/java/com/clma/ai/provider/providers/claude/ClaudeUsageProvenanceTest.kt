package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P2-11b: Anthropic reports cache reads and cache writes separately, and both matter for cost. */
class ClaudeUsageProvenanceTest {

    private fun parse(body: String): TokenUsage {
        val decoder = ClaudeStreamDecoder()
        val m = decoder.javaClass.getDeclaredMethod("parseTokenUsage", JsonObject::class.java)
        m.isAccessible = true
        return m.invoke(decoder, Json.parseToJsonElement(body).jsonObject) as TokenUsage
    }

    @Test
    fun `cache reads are reported and cache writes are kept instead of discarded`() {
        val usage = parse("""{"usage":{"input_tokens":100,"cache_read_input_tokens":40,"cache_creation_input_tokens":25,"output_tokens":7}}""")
        assertEquals(165, usage.promptTokens) // unchanged: reads and writes both stay in the prompt
        assertEquals(40, usage.cachedTokens)
        assertEquals(true, usage.cachedTokensReported)
        assertEquals(25, usage.cacheWriteTokens)

        val silent = parse("""{"usage":{"input_tokens":100,"output_tokens":7}}""")
        assertEquals(0, silent.cachedTokens)
        assertEquals(false, silent.cachedTokensReported)
        assertNull(silent.cacheWriteTokens)
    }

    @Test
    fun `an explicit null cache field does not throw`() {
        val usage = parse("""{"usage":{"input_tokens":100,"cache_read_input_tokens":null,"cache_creation_input_tokens":null,"output_tokens":7}}""")
        assertEquals(0, usage.cachedTokens)
        assertEquals(false, usage.cachedTokensReported)
    }
}
