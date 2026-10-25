package com.clma.app.data.vector

import com.clma.ai.provider.ProviderSetting
import com.clma.ai.provider.providers.openai.EmbeddingsAPI
import okhttp3.OkHttpClient

/**
 * Embeds a batch of texts with a cloud model.
 *
 * An interface because [EmbeddingService] is the one place that has to be right about *when* a
 * cloud model is used rather than which, and that question can be tested with a recorder that
 * never opens a socket - the same reason the local embedder takes its native half as a parameter.
 */
interface CloudEmbeddingCaller {
    suspend fun embed(candidate: CloudEmbeddingCandidate, inputs: List<String>): List<FloatArray>
}

/**
 * The real caller: [EmbeddingsAPI] over the app's shared HTTP client.
 *
 * The providers are read through a lambda rather than captured, because this object outlives any
 * settings snapshot: a key pasted, a base URL corrected or a provider disabled between two rounds
 * of indexing has to be in effect for the next request, not for the next launch.
 *
 * A provider that is gone, or that this build cannot speak to, fails here with a sentence naming
 * it. Both are reachable from the UI - a provider can be deleted while the index is running, and
 * the candidate carries its own `supported` flag so the settings screen can say so first - and
 * neither should look like a bug in the index.
 */
class CloudEmbeddingClient(
    private val client: OkHttpClient,
    private val providers: suspend () -> List<ProviderSetting>,
) : CloudEmbeddingCaller {

    private val api by lazy { EmbeddingsAPI(client) }

    override suspend fun embed(
        candidate: CloudEmbeddingCandidate,
        inputs: List<String>,
    ): List<FloatArray> {
        val provider = CloudEmbeddingModels.providerOf(candidate.providerId, providers())
            ?: error("the provider of '${candidate.displayName}' is no longer configured")
        return api.embed(provider, candidate.modelId, inputs)
    }
}
