package me.rerere.ai.provider

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    /**
     * Per-model thinking depth. Null falls back to the assistant-level setting
     * (migration path); once the user adjusts it here, this takes precedence.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reasoningLevel: ReasoningLevel? = null,
    // Optional capability/pricing metadata, populated from OpenRouter's /models endpoint.
    val contextLength: Int? = null,
    val supportedParameters: List<String> = emptyList(),
    val pricePromptPerToken: Double? = null,
    val priceCompletionPerToken: Double? = null,
    /**
     * P2-11d - explicit user-owned pricing for this model, including off-peak rates. Null
     * falls back to the per-token metadata above and then to no cost at all.
     */
    val pricing: ModelPricing? = null,
)

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    VIDEO,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,

    /** D3 — the model accepts audio input (recordings it can listen to). */
    AUDIO,

    /** D3 — the model accepts video input (clips it can watch). */
    VIDEO,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}



