package com.clma.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeWebSearchTest {
    @Test
    fun `native search depends on the request adapter and not the provider name`() {
        assertEquals(true, supportsNativeWebSearch(ProviderSetting.OpenAI(useResponseApi = true)))
        assertEquals(false, supportsNativeWebSearch(ProviderSetting.OpenAI(useResponseApi = false)))
        assertEquals(true, supportsNativeWebSearch(ProviderSetting.Google()))
        assertEquals(true, supportsNativeWebSearch(ProviderSetting.Claude()))
        assertEquals(true, supportsNativeWebSearch(ProviderSetting.Codex()))
        assertEquals(true, supportsNativeWebSearch(ProviderSetting.Grok()))
        assertEquals(true, supportsNativeWebSearch(ProviderSetting.GeminiOAuth()))
    }
}
