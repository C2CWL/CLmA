package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChatCompletionsAPI token usage parsing.
 */
class ChatCompletionsUsageParsingTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // Helper to invoke private parseTokenUsage via reflection
    private fun parseTokenUsage(usage: JsonObject): TokenUsage? {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "parseTokenUsage",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(api, usage) as TokenUsage?
    }

    // #1576: cached tokens 按 provider 方言兜底解析
    @Test
    fun `cached tokens fall back across provider dialects`() {
        fun usage(jsonStr: String) = parseTokenUsage(Json.parseToJsonElement(jsonStr).jsonObject)

        // OpenAI 官方嵌套格式
        assertEquals(
            12,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_tokens_details":{"cached_tokens":12}}""")?.cachedTokens
        )
        // Moonshot 顶层 cached_tokens
        assertEquals(
            7,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"cached_tokens":7}""")?.cachedTokens
        )
        // DeepSeek 顶层 prompt_cache_hit_tokens
        assertEquals(
            5,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_cache_hit_tokens":5,"prompt_cache_miss_tokens":3}""")?.cachedTokens
        )
        // 嵌套字段优先于顶层兜底
        assertEquals(
            12,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_tokens_details":{"cached_tokens":12},"cached_tokens":7}""")?.cachedTokens
        )
        // 都没有时为 0
        assertEquals(
            0,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}""")?.cachedTokens
        )
    }

    // P2-11a: cache fields carry provenance, so an unreported field is never read as a zero.
    @Test
    fun `cache provenance separates an unreported field from a real zero`() {
        fun usage(jsonStr: String) =
            parseTokenUsage(Json.parseToJsonElement(jsonStr).jsonObject)!!

        // DeepSeek reports hit + miss; a hit of 0 must stay distinguishable from "no field".
        val deepseek = usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":2}""")
        assertEquals(true, deepseek.cachedTokensReported)
        assertEquals(0, deepseek.cachedTokens)
        assertEquals(2, deepseek.cacheMissTokens)

        // No cache field at all: cachedTokens stays 0 for backward compatibility, but the
        // provenance flag says unknown, so hit-rate statistics can exclude this record.
        val silent = usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}""")
        assertEquals(false, silent.cachedTokensReported)
        assertNull(silent.cacheMissTokens)
        assertNull(silent.reasoningTokens)
    }
}
