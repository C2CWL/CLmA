package com.clma.app.data.vector

import com.clma.ai.provider.ModelType
import com.clma.ai.provider.ProviderSetting

/**
 * The user's configured providers, read as a list of cloud embedding choices.
 *
 * The mechanical half of [CloudEmbeddingRules]: everything here is a translation from the provider
 * graph to the flat projection those rules work on, so that the rules themselves stay testable
 * without one.
 *
 * The selection rule is "the model is marked as an embedding model" - `ModelType.EMBEDDING`, which
 * the model editor already offers next to chat, image and video. Nothing is guessed from a name:
 * a provider's catalogue contains hundreds of models and only the user knows which of them they
 * configured a key for the purpose of embedding with.
 */
object CloudEmbeddingModels {

    /** Every model marked as an embedding model, in provider order, marked as callable or not. */
    fun candidates(providers: List<ProviderSetting>): List<CloudEmbeddingCandidate> =
        providers.flatMap { provider ->
            val supported = speaksEmbeddings(provider)
            provider.models
                .filter { it.type == ModelType.EMBEDDING }
                .map { model ->
                    CloudEmbeddingCandidate(
                        providerId = provider.id.toString(),
                        providerName = provider.name,
                        modelUuid = model.id.toString(),
                        modelId = model.modelId,
                        displayName = model.displayName.ifBlank { model.modelId },
                        supported = supported,
                        contextLength = model.contextLength,
                    )
                }
        }

    /**
     * Whether this build can call an embeddings endpoint on this provider.
     *
     * Only the OpenAI-compatible ones, which is one case and not an oversight: `ProviderSetting.OpenAI`
     * is what every OpenAI-compatible endpoint is configured as - OpenAI itself, OpenRouter,
     * SiliconFlow, DashScope's compatible mode, a self-hosted vLLM - so one request shape covers
     * all of them. Google's `:embedContent` is a different protocol with a different response, the
     * on-device providers ([ProviderSetting.LlamaCppLocal], [ProviderSetting.LiteRtLocal],
     * [ProviderSetting.AICore]) embed through the local path rather than through a provider, and
     * Claude publishes no embeddings endpoint at all. Each of those is refused by name rather than
     * by a request that comes back 404.
     */
    fun speaksEmbeddings(provider: ProviderSetting): Boolean = provider is ProviderSetting.OpenAI

    /**
     * The provider a candidate came from, or null when it has been deleted since.
     *
     * Returns the settable type rather than the sealed base because the transport only knows how to
     * talk to one of them, and the cast is the statement of that.
     */
    fun providerOf(providerId: String, providers: List<ProviderSetting>): ProviderSetting.OpenAI? =
        providers.firstOrNull { it.id.toString() == providerId } as? ProviderSetting.OpenAI
}
