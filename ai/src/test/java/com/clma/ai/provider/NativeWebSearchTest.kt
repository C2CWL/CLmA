package com.clma.ai.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWebSearchTest {
    @Test
    fun `native search depends on the request adapter and not the provider name`() {
        val nativeAdapters = listOf(
            ProviderSetting.OpenAI(useResponseApi = true),
            ProviderSetting.Claude(),
            ProviderSetting.Google(),
            ProviderSetting.Codex(),
            ProviderSetting.Grok(),
            ProviderSetting.GeminiOAuth(),
        )
        nativeAdapters.forEach { assertTrue(it.name, it.supportsNativeWebSearch()) }

        val otherAdapters = listOf(
            ProviderSetting.OpenAI(useResponseApi = false),
            ProviderSetting.OpenAI(baseUrl = "https://api.groq.com/openai/v1"),
            ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1"),
            ProviderSetting.LlamaCppLocal(),
        )
        otherAdapters.forEach { assertFalse(it.name, it.supportsNativeWebSearch()) }
    }

    @Test
    fun `configured search is effective only on an enabled native adapter`() {
        val model = Model(tools = setOf(BuiltInTools.Search))
        val provider = ProviderSetting.OpenAI(useResponseApi = true)

        assertTrue(model.effectiveBuiltInSearch(provider))
        assertFalse(model.effectiveBuiltInSearch(provider.copy(enabled = false)))
        assertFalse(model.effectiveBuiltInSearch(provider.copy(useResponseApi = false)))
        assertFalse(model.effectiveBuiltInSearch(null))
        assertFalse(Model().effectiveBuiltInSearch(provider))
    }
}
