package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P2-11b: the stream decoders must fill the cache provenance fields, so that "the provider
 * reported a cache miss" stays distinguishable from "this provider does not report cache
 * fields at all". Before this, both cases were stored as cachedTokens = 0.
 */
class StreamDecoderUsageProvenanceTest {

    private fun invoke(target: Any, method: String, json: String): TokenUsage? {
        val m = target.javaClass.getDeclaredMethod(method, JsonObject::class.java)
        m.isAccessible = true
        return m.invoke(target, Json.parseToJsonElement(json).jsonObject) as TokenUsage?
    }

    private fun chat(json: String) = invoke(ChatCompletionsStreamDecoder(), "parseUsage", json)!!
    private fun responses(json: String) = invoke(ResponseApiStreamDecoder(), "parseUsage", json)!!

    @Test
    fun `chat completions reports provenance across the three dialects`() {
        val openai = chat("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_tokens_details":{"cached_tokens":12}}""")
        assertEquals(12, openai.cachedTokens)
        assertEquals(true, openai.cachedTokensReported)

        val deepseek = chat("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":2}""")
        assertEquals(0, deepseek.cachedTokens)
        assertEquals(true, deepseek.cachedTokensReported)
        assertEquals(2, deepseek.cacheMissTokens)

        val silent = chat("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}""")
        assertEquals(0, silent.cachedTokens)
        assertEquals(false, silent.cachedTokensReported)
        assertNull(silent.cacheMissTokens)
        assertNull(silent.reasoningTokens)
    }

    @Test
    fun `responses api maps the token detail blocks`() {
        val reported = responses(
            """{"input_tokens":10,"output_tokens":2,"total_tokens":12,"input_tokens_details":{"cached_tokens":4},"output_tokens_details":{"reasoning_tokens":7}}"""
        )
        assertEquals(4, reported.cachedTokens)
        assertEquals(true, reported.cachedTokensReported)
        assertEquals(7, reported.reasoningTokens)

        val silent = responses("""{"input_tokens":10,"output_tokens":2,"total_tokens":12}""")
        assertEquals(false, silent.cachedTokensReported)
        assertNull(silent.reasoningTokens)
    }
}
