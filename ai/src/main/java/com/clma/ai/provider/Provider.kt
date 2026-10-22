package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.VideoGenerationItem
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    suspend fun getBalance(providerSetting: T): String {
        return "TODO"
    }

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk>

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem>

    suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported")
    }

    /**
     * Text-to-video. Not every provider has a video model, so the default refuses loudly rather
     * than silently doing nothing — the tool layer turns the failure into a structured envelope.
     * Providers that do (currently the OpenAI-compatible one, for DashScope Wan and Volcengine
     * Seedance) override it.
     */
    suspend fun generateVideo(
        providerSetting: ProviderSetting,
        params: VideoGenerationParams,
    ): Flow<VideoGenerationItem> {
        error("Video generation is not supported")
    }
}

@Serializable
data class TextGenerationResult(
    val id: String,
    val model: String,
    val message: UIMessage,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
)

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    /**
     * Number of additional attempts permitted when a streaming response fails before any
     * meaningful output is received. Providers that cannot safely replay a stream ignore it.
     */
    val maxStreamRetries: Int = 0,
    val tools: List<Tool> = emptyList(),
    /**
     * Recover a tool call a model wrote as literal content text (`<tool_call>{…}</tool_call>`)
     * instead of as a structured call. Only the OpenAI-compatible providers implement it today;
     * see `Settings.parseTextToolCalls` for the user-facing switch.
     */
    val textToolCallParsing: Boolean = true,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    val sessionId: String? = Uuid.random().toString(),
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageEditParams(
    val model: Model,
    val prompt: String,
    val images: List<String>,
    val numOfImages: Int = 1,
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class VideoGenerationParams(
    val model: Model,
    val prompt: String,
    /**
     * Wan and Seedance are landscape-shaped by default; the tool's own default is landscape too,
     * so a careless call does not silently produce a portrait clip.
     */
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.LANDSCAPE,
    /**
     * Requested clip length in seconds. `null` leaves the choice to the model/vendor (Wan's fixed
     * 5 s, Seedance's own default). Not every video model accepts a configurable duration — see the
     * per-provider request builders for the clamping.
     */
    val durationSeconds: Int? = null,
    /**
     * How many clips to produce. Neither wired vendor takes an `n` for video, so a count > 1 is
     * emulated with **sequential** calls (each of which can take minutes) — see the provider.
     */
    val numOfVideos: Int = 1,
    /**
     * Local file paths used as the **first frame** (image-to-video). Empty means plain
     * text-to-video. Only the first entry is used: the DashScope first-frame endpoint takes a
     * single `img_url`, and Seedance's `first_frame` role is likewise one image. It is read and
     * inlined as a data URI, exactly like `ImageEditParams.images`.
     */
    val sourceImages: List<String> = emptyList(),
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
