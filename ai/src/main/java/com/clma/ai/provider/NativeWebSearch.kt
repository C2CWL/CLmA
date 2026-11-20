package com.clma.ai.provider

/** Whether this adapter serializes [BuiltInTools.Search], not a server or model capability probe. */
fun ProviderSetting?.supportsNativeWebSearch(): Boolean = when (this) {
    is ProviderSetting.OpenAI -> useResponseApi
    is ProviderSetting.Google,
    is ProviderSetting.Claude,
    is ProviderSetting.Codex,
    is ProviderSetting.Grok,
    is ProviderSetting.GeminiOAuth -> true
    is ProviderSetting.LlamaCppLocal, null -> false
}

fun Model?.effectiveBuiltInSearch(provider: ProviderSetting?): Boolean =
    this != null && BuiltInTools.Search in tools &&
        provider?.enabled == true && provider.supportsNativeWebSearch()
