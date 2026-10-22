package com.clma.app.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.clma.ai.core.ReasoningLevel
import com.clma.ai.provider.CustomBody
import com.clma.ai.provider.ProviderManager
import com.clma.ai.provider.TextGenerationParams
import com.clma.ai.registry.ModelRegistry
import com.clma.ai.ui.StreamChunkHandler
import com.clma.ai.ui.UIMessage
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.findModelById
import com.clma.app.data.datastore.findProvider
import com.clma.app.utils.applyPlaceholders
import java.util.Locale
import com.clma.app.data.usage.UsageCallContext
import com.clma.app.data.usage.UsagePurpose
import kotlinx.coroutines.withContext

class TranslationHandler(
    private val providerManager: ProviderManager,
) {
    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null,
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            val streamChunkHandler = StreamChunkHandler(model)

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = streamChunkHandler.handle(messages, chunk)
                val translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val result = withContext(UsageCallContext(purpose = UsagePurpose.TRANSLATION)) { providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH)),
                                )
                            },
                        )
                    ),
                ),
            ) }
            val translatedText = result.message.toText()

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
