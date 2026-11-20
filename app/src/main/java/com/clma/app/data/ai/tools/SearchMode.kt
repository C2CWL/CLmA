package com.clma.app.data.ai.tools

import com.clma.ai.provider.BuiltInTools
import com.clma.ai.provider.Model
import com.clma.ai.provider.ProviderSetting
import com.clma.ai.provider.supportsNativeWebSearch

internal enum class SearchMode {
    OFF,
    LOCAL,
    BUILT_IN,
}

internal fun Model.effectiveBuiltInSearch(provider: ProviderSetting?): Boolean =
    BuiltInTools.Search in tools && provider != null && supportsNativeWebSearch(provider)

internal fun shouldUseExternalWebSearch(
    assistant: com.clma.app.data.model.Assistant,
    model: Model,
    provider: ProviderSetting?,
): Boolean = assistant.enableWebSearch && !model.effectiveBuiltInSearch(provider)
