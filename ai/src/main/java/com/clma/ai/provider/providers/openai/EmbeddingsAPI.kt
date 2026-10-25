package com.clma.ai.provider.providers.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.clma.ai.provider.ProviderSetting
import com.clma.ai.util.KeyRoulette
import com.clma.ai.util.configureReferHeaders
import com.clma.ai.util.json
import com.clma.ai.util.mergeCustomHeaders
import com.clma.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One call to an OpenAI-compatible embeddings endpoint.
 *
 * The transport half of [EmbeddingsRules]: the same shape as [ChatCompletionsAPI] next door,
 * because a provider configured for chat is a provider configured for this - the key, the base
 * URL, the custom headers and the key rotation all come from the same [ProviderSetting.OpenAI].
 *
 * Only the OpenAI-compatible shape is spoken here, deliberately. Google's `:embedContent` is a
 * different protocol, Claude publishes no embeddings endpoint, and the on-device providers embed
 * through the local path instead. A provider of one of those kinds is refused before a request is
 * built (see `CloudEmbeddingModels.speaksEmbeddings`), so the user is told why rather than handed
 * a 404 from a URL that was never going to work.
 */
class EmbeddingsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default(),
) {

    suspend fun embed(
        setting: ProviderSetting.OpenAI,
        modelId: String,
        inputs: List<String>,
    ): List<FloatArray> = withContext(Dispatchers.IO) {
        if (inputs.isEmpty()) return@withContext emptyList()

        val key = keyRoulette.next(setting.apiKey, setting.id.toString())
        val url = "${setting.baseUrl.trimEnd('/')}${EmbeddingsRules.PATH}"
        val request = Request.Builder()
            .url(url)
            .headers(setting.mergeCustomHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(url)
            .post(
                json.encodeToString(EmbeddingsRules.requestBody(modelId, inputs))
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        val response = client.newCall(request).await()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("the embedding request failed: ${response.code} ${body.take(500)}")
        }
        EmbeddingsRules.parseVectors(body)
    }
}
