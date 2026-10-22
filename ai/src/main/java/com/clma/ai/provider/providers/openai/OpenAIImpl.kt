package com.clma.ai.provider.providers.openai

import kotlinx.coroutines.flow.Flow
import com.clma.ai.provider.ProviderSetting
import com.clma.ai.provider.TextGenerationResult
import com.clma.ai.provider.TextGenerationParams
import com.clma.ai.ui.StreamChunk
import com.clma.ai.ui.UIMessage

interface OpenAIImpl {
    suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult

    suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk>
}
