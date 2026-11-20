package com.clma.ai.provider

import com.clma.ai.provider.providers.openai.OpenAIProvider

/** Whether the active provider adapter actually serializes native web-search configuration. */
fun supportsNativeWebSearch(provider: ProviderSetting): Boolean = when (provider) {
    is ProviderSetting.OpenAI -> provider.useResponseApi
    is ProviderSetting.Google,
    is ProviderSetting.Claude,
    is ProviderSetting.Codex,
    is ProviderSetting.Grok,
    is ProviderSetting.GeminiOAuth -> true
    else -> false
}
