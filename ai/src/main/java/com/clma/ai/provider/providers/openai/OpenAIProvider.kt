package me.rerere.ai.provider.providers.openai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.VideoGenerationParams
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.VideoGenerationItem
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "OpenAIProvider"

// Same shape as ClaudeProvider.MINIMAX_FALLBACK_MODELS — see comment there for
// why a fallback is needed (Minimax's /v1/models returns `{"object":"","data":null}`
// even with a valid key, despite their published OpenAPI spec). Duplicated rather
// than shared because both providers are otherwise self-contained and a util
// module just for this would be overkill.
private val MINIMAX_FALLBACK_MODELS = listOf(
    "MiniMax-M2.7",
    "MiniMax-M2.7-highspeed",
    "MiniMax-M2.5",
    "MiniMax-M2.5-highspeed",
    "MiniMax-M2.1",
    "MiniMax-M2.1-highspeed",
    "MiniMax-M2",
).map { Model(modelId = it, displayName = it) }

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette)


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            // OpenRouter's /models returns text-output models only by default, which hides the
            // image-only models (FLUX, Recraft, Seedream, ...). Ask for text and image explicitly.
            val modelsUrl = if (providerSetting.baseUrl.contains("openrouter.ai")) {
                "${providerSetting.baseUrl}/models?output_modalities=text,image"
            } else {
                "${providerSetting.baseUrl}/models"
            }
            val request = Request.Builder()
                .url(modelsUrl)
                .headers(providerSetting.mergeCustomHeaders())
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            val response = client.newCall(request).await()
            val bodyStr = response.body.string()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} $bodyStr")
            }

            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            // `as? JsonArray` handles both an absent `data` key (Kotlin null)
            // and a JSON `null` value (`JsonNull`, which is a non-null Kotlin
            // object that throws if you call `.jsonArray` on it).
            val data = bodyJson["data"] as? JsonArray
            if (data == null) {
                // Some providers (Minimax, etc.) return HTTP 200 with an error
                // envelope instead of a 4xx. Surface the error so the user sees
                // why the model list is empty rather than a silent blank sheet.
                val baseResp = bodyJson["base_resp"] as? JsonObject
                val statusCode = baseResp?.get("status_code")?.jsonPrimitive?.intOrNull
                if (statusCode != null && statusCode != 0) {
                    val msg = baseResp["status_msg"]?.jsonPrimitive?.contentOrNull
                    error("Failed to get models: ${msg ?: "status_code=$statusCode"}")
                }
                val errMsg = (bodyJson["error"] as? JsonObject)?.get("message")
                    ?.jsonPrimitive?.contentOrNull
                if (errMsg != null) {
                    error("Failed to get models: $errMsg")
                }
                if (providerSetting.baseUrl.contains("api.minimax.io", ignoreCase = true)) {
                    return@withContext MINIMAX_FALLBACK_MODELS
                }
                error("Failed to get models: response has no `data` field")
            }

            val isOpenRouter = providerSetting.baseUrl.contains("openrouter.ai", ignoreCase = true)
            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                if (isOpenRouter) {
                    // Rich capability + pricing detection from OpenRouter's catalog.
                    openRouterModelFromJson(modelObj)
                } else {
                    val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    Model(
                        modelId = id,
                        displayName = id,
                    )
                }
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .headers(providerSetting.mergeCustomHeaders())
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        // OpenRouter has a dedicated Images API for image-only models (FLUX.2, Recraft,
        // Seedream, gpt-image, Qwen-Image, ...) that never speak /chat/completions with
        // modalities:["image","text"]. Fall back to that older chat-completions path only
        // when /images itself 404s/405s (an OpenRouter-compatible proxy without it) - decided
        // once, on the first attempt, and reused for the rest. Neither path takes `n` (see
        // buildOpenRouterImagesRequestBody), so count > 1 is emulated with sequential calls.
        if (providerSetting.baseUrl.contains("openrouter.ai", ignoreCase = true)) {
            var useChatCompletionsFallback = false
            val items = withContext(Dispatchers.IO) {
                collectSequentialImages(params.numOfImages.coerceAtLeast(1)) {
                    if (useChatCompletionsFallback) {
                        generateImageViaChatCompletions(providerSetting, params, key)
                    } else {
                        generateImageViaOpenRouterImages(providerSetting, params, key) ?: run {
                            useChatCompletionsFallback = true
                            generateImageViaChatCompletions(providerSetting, params, key)
                        }
                    }
                }
            }
            items.forEach { emit(it) }
            return@flow
        }

        // DashScope's OpenAI-compatible base URL has no /images/generations - it answers 404
        // while /chat/completions on the same host answers 401 - so Qwen-Image / Wanx go through
        // the provider's native async task API instead. See DashScopeImageRequest for the shapes.
        // Other providers never enter this branch, so their path is unchanged. One task already
        // returns `n` images, so this is a single call, not collectSequentialImages.
        if (isDashScopeBaseUrl(providerSetting.baseUrl)) {
            val items = withContext(Dispatchers.IO) {
                generateImageViaDashScope(providerSetting, params, key)
            }
            items.forEach { emit(it) }
            return@flow
        }

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                // xAI rejects the OpenAI `size` parameter with a 400 (upstream issue #1602),
                // so leave it off for Grok and let the endpoint pick its own dimensions.
                val isGrok = providerSetting.baseUrl.contains("x.ai", ignoreCase = true) ||
                    params.model.modelId.contains("grok", ignoreCase = true)
                if (!isGrok) {
                    put(
                        "size", when (params.aspectRatio) {
                            ImageAspectRatio.SQUARE -> "1024x1024"
                            ImageAspectRatio.LANDSCAPE -> "1536x1024"
                            ImageAspectRatio.PORTRAIT -> "1024x1536"
                        }
                    )
                }
            }
                .mergeCustomBody(params.customBody)
        )

        Log.i(TAG, "generateImage: $requestBody")

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    /**
     * DashScope (Aliyun Bailian) text-to-image. Two endpoint shapes exist on the same host and a
     * model only works on the one that matches its family, so this picks the likely one from the
     * model id and retries the other when the provider answers the "model does not match this
     * endpoint" rejection:
     *
     *  - **Legacy** (`qwen-image`, `qwen-image-plus`, `wanx*`, `wan2.*`): asynchronous task API —
     *    submit `text2image/image-synthesis` (with `X-DashScope-Async: enable`), then poll
     *    `GET /api/v1/tasks/{id}` for `output.results[].url`.
     *  - **New generation** (`qwen-image-2.x` / `qwen-image-3.x`): the multimodal endpoint
     *    `multimodal-generation/generation`, which is **synchronous** and returns the image URLs
     *    inline at `output.choices[].message.content[].image`.
     *
     * The OpenAI-compatible base URL this provider normally uses has no `/images/generations`, so
     * the generic path 404s there (see [DashScopeImageRequest]). `n` is sent on the request, so one
     * call yields several images — unlike the OpenRouter path, this is not N sequential calls.
     */
    private suspend fun generateImageViaDashScope(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
        key: String,
    ): List<ImageGenerationItem> {
        val root = dashScopeNativeRoot(providerSetting.baseUrl)
            ?: error("Not a DashScope base URL: ${providerSetting.baseUrl}")

        val preferredMultimodal = dashScopeUsesMultimodalEndpoint(params.model.modelId)
        val first = runCatching { submitDashScopeImage(root, providerSetting, params, key, preferredMultimodal) }
        val failure = first.exceptionOrNull()
        if (failure == null) return first.getOrThrow()
        // Never swallow coroutine cancellation as a provider failure.
        if (failure is CancellationException) throw failure

        // A `url error` rejection means the model and the endpoint disagree about the API shape.
        // Retry once on the other endpoint so a model we did not enumerate still works.
        if (failure is DashScopeSubmitRejected && failure.endpointMismatch) {
            return submitDashScopeImage(root, providerSetting, params, key, !preferredMultimodal)
        }
        throw failure
    }

    /** A rejected DashScope image submit; [endpointMismatch] flags the retryable "url error". */
    private class DashScopeSubmitRejected(
        message: String,
        val endpointMismatch: Boolean,
    ) : IllegalStateException(message)

    /**
     * Runs one DashScope image request against the shape selected by [multimodal] (sync
     * messages body) or its opposite (async prompt task), and returns the downloaded images.
     */
    private suspend fun submitDashScopeImage(
        root: String,
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
        key: String,
        multimodal: Boolean,
    ): List<ImageGenerationItem> {
        val (path, body) = if (multimodal) {
            DASHSCOPE_MULTIMODAL_GENERATION_PATH to buildDashScopeMultimodalImageRequestBody(
                model = params.model,
                prompt = params.prompt,
                aspectRatio = params.aspectRatio,
                numOfImages = params.numOfImages,
            )
        } else {
            DASHSCOPE_IMAGE_SYNTHESIS_PATH to buildDashScopeImageRequestBody(
                model = params.model,
                prompt = params.prompt,
                aspectRatio = params.aspectRatio,
                numOfImages = params.numOfImages,
            )
        }
        val submitBody = body.mergeCustomBody(params.customBody)

        val submitRequest = Request.Builder()
            .url("$root$path")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .apply { if (!multimodal) addHeader("X-DashScope-Async", "enable") }
            .post(json.encodeToString(submitBody).toRequestBody("application/json".toMediaType()))
            .build()

        val submitResponse = client.newCall(submitRequest).await()
        val submitText = submitResponse.body?.string().orEmpty()
        if (!submitResponse.isSuccessful) {
            throw DashScopeSubmitRejected(
                message = "Failed to submit image task: ${submitResponse.code} $submitText",
                endpointMismatch = isDashScopeEndpointModelMismatch(submitText),
            )
        }

        // New generation: the synchronous response already carries the images.
        if (multimodal) {
            val urls = parseDashScopeMultimodalImageUrls(submitText)
            if (urls.isEmpty()) {
                error("DashScope returned no images for ${params.model.modelId}: $submitText")
            }
            return urls.map { downloadImageAsBase64(it) }
        }

        // Legacy: poll the async task until it finishes.
        val taskId = parseDashScopeTaskId(submitText)
            ?: error("No task_id in DashScope response: $submitText")
        return pollDashScopeImageTask(root, key, taskId)
    }

    /**
     * Polls `GET /api/v1/tasks/{taskId}` until the task reaches a terminal status and returns its
     * downloaded images. Shared by the text-to-image submit and the legacy image-**edit** submit,
     * which differ only in the body they post and both answer with a `task_id`.
     */
    private suspend fun pollDashScopeImageTask(
        root: String,
        key: String,
        taskId: String,
    ): List<ImageGenerationItem> {
        val taskRequest = Request.Builder()
            .url("$root${dashScopeTaskPath(taskId)}")
            .addHeader("Authorization", "Bearer $key")
            .build()

        repeat(DASHSCOPE_POLL_ATTEMPTS) {
            delay(DASHSCOPE_POLL_INTERVAL_MS)
            val pollResponse = client.newCall(taskRequest).await()
            val pollText = pollResponse.body?.string().orEmpty()
            if (!pollResponse.isSuccessful) {
                error("Failed to poll image task $taskId: ${pollResponse.code} $pollText")
            }
            when (parseDashScopeTaskStatus(pollText)) {
                "SUCCEEDED" -> {
                    val urls = parseDashScopeImageUrls(pollText)
                    if (urls.isEmpty()) error("DashScope task $taskId succeeded with no images: $pollText")
                    return urls.map { downloadImageAsBase64(it) }
                }

                "FAILED", "CANCELED", "UNKNOWN" -> error(
                    "DashScope image task $taskId did not succeed: " +
                        (parseDashScopeTaskMessage(pollText) ?: pollText),
                )

                // PENDING / RUNNING - keep polling.
                else -> Unit
            }
        }
        error("DashScope image task $taskId timed out after $DASHSCOPE_POLL_ATTEMPTS polls")
    }

    /**
     * OpenRouter image generation via the dedicated `POST {baseUrl}/images` endpoint. Returns
     * null (rather than throwing) when the endpoint itself looks missing (405, or a 404 whose
     * body isn't OpenRouter's JSON error shape - e.g. an HTML/empty body from a proxy that
     * never added it), so the caller can fall back to the older `/chat/completions` image
     * path. A per-model 404 ("model not found", a real JSON error body) is surfaced instead.
     */
    private suspend fun generateImageViaOpenRouterImages(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
        key: String,
        inputReferences: List<String> = emptyList(),
    ): List<ImageGenerationItem>? {
        val body = buildOpenRouterImagesRequestBody(
            model = params.model,
            prompt = params.prompt,
            aspectRatio = params.aspectRatio,
            inputReferences = inputReferences,
        ).mergeCustomBody(params.customBody)

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val response = client.newCall(request).await()
        val bodyStr = response.body.string()
        if (shouldFallbackToChatCompletionsImage(response.code, bodyStr)) return null
        if (!response.isSuccessful) {
            error(extractOpenRouterErrorMessage(response.code, bodyStr))
        }
        return parseOpenRouterImagesResponse(bodyStr)
    }

    /** OpenRouter image generation via /chat/completions with modalities:["image","text"]. */
    private suspend fun generateImageViaChatCompletions(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
        key: String,
    ): List<ImageGenerationItem> {
        val body = buildOpenRouterChatCompletionsImageBody(
            modelId = params.model.modelId,
            prompt = params.prompt,
            aspectRatio = params.aspectRatio,
        ).mergeCustomBody(params.customBody)

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val response = client.newCall(request).await()
        val bodyStr = response.body.string()
        if (!response.isSuccessful) {
            error("Failed to generate image: ${response.code} $bodyStr")
        }
        return parseOpenRouterChatCompletionsImageResponse(bodyStr)
    }

    /** OpenRouter image edit via /chat/completions with input images as image_url content parts. */
    private suspend fun editImageViaChatCompletions(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageEditParams,
        key: String,
        inputReferences: List<String>,
    ): List<ImageGenerationItem> {
        val body = buildOpenRouterChatCompletionsImageBody(
            modelId = params.model.modelId,
            prompt = params.prompt,
            aspectRatio = params.aspectRatio,
            inputReferences = inputReferences,
        ).mergeCustomBody(params.customBody)

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val response = client.newCall(request).await()
        val bodyStr = response.body.string()
        if (!response.isSuccessful) {
            error("Failed to edit image: ${response.code} $bodyStr")
        }
        return parseOpenRouterChatCompletionsImageResponse(bodyStr)
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        // Same dedicated-endpoint-first, chat-completions-fallback rule as generateImage
        // (decided once, on the first attempt), with the source images always sent as
        // input_references (data URIs) - their presence is the edit request itself, not a
        // capability to gate on. `n` is never sent; count > 1 is sequential calls.
        if (providerSetting.baseUrl.contains("openrouter.ai", ignoreCase = true)) {
            val inputReferences = withContext(Dispatchers.IO) {
                params.images.map { path ->
                    val file = File(path)
                    require(file.exists()) { "Image file does not exist: $path" }
                    file.toDataUri()
                }
            }
            val genParams = ImageGenerationParams(
                model = params.model,
                prompt = params.prompt,
                aspectRatio = params.aspectRatio,
                customHeaders = params.customHeaders,
                customBody = params.customBody,
            )
            var useChatCompletionsFallback = false
            val items = withContext(Dispatchers.IO) {
                collectSequentialImages(params.numOfImages.coerceAtLeast(1)) {
                    if (useChatCompletionsFallback) {
                        editImageViaChatCompletions(providerSetting, params, key, inputReferences)
                    } else {
                        generateImageViaOpenRouterImages(providerSetting, genParams, key, inputReferences) ?: run {
                            useChatCompletionsFallback = true
                            editImageViaChatCompletions(providerSetting, params, key, inputReferences)
                        }
                    }
                }
            }
            items.forEach { emit(it) }
            return@flow
        }

        // DashScope's OpenAI-compatible base URL has no /images/edits either - same 404 as
        // /images/generations - so an edit goes to the provider's native edit endpoints instead.
        // See editImageViaDashScope; other providers never enter this branch.
        if (isDashScopeBaseUrl(providerSetting.baseUrl)) {
            val items = withContext(Dispatchers.IO) {
                editImageViaDashScope(providerSetting, params, key)
            }
            items.forEach { emit(it) }
            return@flow
        }

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
        bodyBuilder.addFormDataPart(
            "size", when (params.aspectRatio) {
                ImageAspectRatio.SQUARE -> "1024x1024"
                ImageAspectRatio.LANDSCAPE -> "1536x1024"
                ImageAspectRatio.PORTRAIT -> "1024x1536"
            }
        )

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = File(path)
            require(imageFile.exists()) {
                "Image file does not exist: $path"
            }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
            )
        }

        params.customBody.forEach { customBody ->
            val value = when (val element = customBody.value) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(customBody.key, value)
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/edits")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to edit image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    /**
     * Text-to-video for the OpenAI-compatible provider. Five families are wired, all driven on
     * their vendor's **native** async task API rather than the OpenAI surface: DashScope Wan
     * ([DashScopeVideoRequest]), Volcengine Ark / BytePlus Seedance ([VolcengineVideoRequest]),
     * MiniMax Hailuo ([MiniMaxVideoRequest]), Zhipu CogVideoX ([ZhipuVideoRequest]) and
     * SiliconFlow ([SiliconFlowVideoRequest]). The base URL's host picks the family; any other host
     * refuses with a clear message, and the tool layer turns that failure into a structured
     * envelope instead of an opaque `tool_failed`.
     */
    override suspend fun generateVideo(
        providerSetting: ProviderSetting,
        params: VideoGenerationParams
    ): Flow<VideoGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        // Neither vendor takes an `n` for video, so a count > 1 is N sequential jobs (each of which
        // can take minutes) - see collectSequentialImages. The tool caps the count for exactly that
        // reason.
        val items = withContext(Dispatchers.IO) {
            collectSequentialImages(params.numOfVideos.coerceAtLeast(1)) {
                generateVideoOnce(providerSetting, params, key)
            }
        }

        items.forEach { emit(it) }
    }

    /** One vendor job. Split out so [generateVideo] can run it N times for a count > 1. */
    private suspend fun generateVideoOnce(
        providerSetting: ProviderSetting.OpenAI,
        params: VideoGenerationParams,
        key: String,
    ): List<VideoGenerationItem> = when {
        isDashScopeBaseUrl(providerSetting.baseUrl) ->
            generateVideoViaDashScope(providerSetting, params, key)

        isVolcengineArkBaseUrl(providerSetting.baseUrl) ->
            generateVideoViaVolcengine(providerSetting, params, key)

        isMiniMaxBaseUrl(providerSetting.baseUrl) ->
            generateVideoViaMiniMax(providerSetting, params, key)

        isZhipuBaseUrl(providerSetting.baseUrl) ->
            generateVideoViaZhipu(providerSetting, params, key)

        isSiliconFlowBaseUrl(providerSetting.baseUrl) ->
            generateVideoViaSiliconFlow(providerSetting, params, key)

        else -> error(
            "Video generation is not supported for this provider yet " +
                "(${providerSetting.baseUrl}). Configure a DashScope (Wan), Volcengine Ark " +
                "(Seedance), MiniMax (Hailuo), Zhipu (CogVideoX) or SiliconFlow provider.",
        )
    }

    /**
     * Reads the optional first frame (image-to-video) as a `data:` URI. Both vendors accept an
     * inline base64 image, which is why nothing is uploaded anywhere.
     */
    private fun firstFrameDataUri(params: VideoGenerationParams): String? =
        params.sourceImages.firstOrNull()?.let { path ->
            val file = File(path)
            require(file.exists()) { "First-frame image does not exist: $path" }
            file.toDataUri()
        }

    /**
     * DashScope Wan video: submit an async task, poll the shared task endpoint until `SUCCEEDED`,
     * then download the clip. Text-to-video uses `video-synthesis` with `parameters.size`;
     * image-to-video (a first frame was supplied) uses `image2video/video-synthesis` with
     * `input.img_url` instead. See [DashScopeVideoRequest] for both shapes.
     */
    private suspend fun generateVideoViaDashScope(
        providerSetting: ProviderSetting.OpenAI,
        params: VideoGenerationParams,
        key: String,
    ): List<VideoGenerationItem> {
        val root = dashScopeNativeRoot(providerSetting.baseUrl)
            ?: error("Not a DashScope base URL: ${providerSetting.baseUrl}")

        val firstFrame = firstFrameDataUri(params)
        val (path, body) = if (firstFrame == null) {
            DASHSCOPE_VIDEO_SYNTHESIS_PATH to buildDashScopeVideoRequestBody(
                model = params.model,
                prompt = params.prompt,
                aspectRatio = params.aspectRatio,
                durationSeconds = params.durationSeconds,
            )
        } else {
            // i2v: the clip follows the first frame's aspect ratio, so the shape argument is moot.
            DASHSCOPE_IMAGE2VIDEO_SYNTHESIS_PATH to buildDashScopeImageToVideoRequestBody(
                model = params.model,
                prompt = params.prompt,
                firstFrameUrl = firstFrame,
                durationSeconds = params.durationSeconds,
            )
        }
        val submitBody = body.mergeCustomBody(params.customBody)

        val submitRequest = Request.Builder()
            .url("$root$path")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-DashScope-Async", "enable")
            .post(json.encodeToString(submitBody).toRequestBody("application/json".toMediaType()))
            .build()

        val submitResponse = client.newCall(submitRequest).await()
        val submitText = submitResponse.body?.string().orEmpty()
        if (!submitResponse.isSuccessful) {
            error("Failed to submit DashScope video task: ${submitResponse.code} $submitText")
        }

        val taskId = parseDashScopeTaskId(submitText)
            ?: error("No task_id in DashScope video response: $submitText")

        val pollRequest = Request.Builder()
            .url("$root${dashScopeTaskPath(taskId)}")
            .addHeader("Authorization", "Bearer $key")
            .build()

        repeat(DASHSCOPE_VIDEO_POLL_ATTEMPTS) {
            delay(DASHSCOPE_VIDEO_POLL_INTERVAL_MS)
            val pollResponse = client.newCall(pollRequest).await()
            val pollText = pollResponse.body?.string().orEmpty()
            if (!pollResponse.isSuccessful) {
                error("Failed to poll DashScope video task $taskId: ${pollResponse.code} $pollText")
            }
            when (parseDashScopeTaskStatus(pollText)) {
                "SUCCEEDED" -> {
                    val url = parseDashScopeVideoUrl(pollText)
                        ?: error("DashScope video task $taskId succeeded with no video_url: $pollText")
                    return listOf(downloadVideoAsBase64(url))
                }

                "FAILED", "CANCELED", "UNKNOWN" -> error(
                    "DashScope video task $taskId did not succeed: " +
                        (parseDashScopeTaskMessage(pollText) ?: pollText),
                )

                // PENDING / RUNNING - keep polling.
                else -> Unit
            }
        }
        error("DashScope video task $taskId timed out after $DASHSCOPE_VIDEO_POLL_ATTEMPTS polls")
    }

    /**
     * Volcengine Ark (Seedance) text-to-video: create a contents task, poll until `succeeded`,
     * then download the clip. See [VolcengineVideoRequest] for the shapes.
     */
    private suspend fun generateVideoViaVolcengine(
        providerSetting: ProviderSetting.OpenAI,
        params: VideoGenerationParams,
        key: String,
    ): List<VideoGenerationItem> {
        val base = providerSetting.baseUrl.trimEnd('/')

        val createBody = buildVolcengineVideoRequestBody(
            model = params.model,
            prompt = params.prompt,
            aspectRatio = params.aspectRatio,
            durationSeconds = params.durationSeconds,
            firstFrameUrl = firstFrameDataUri(params),
        ).mergeCustomBody(params.customBody)

        val createRequest = Request.Builder()
            .url("$base$VOLCENGINE_CREATE_VIDEO_TASK_PATH")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(createBody).toRequestBody("application/json".toMediaType()))
            .build()

        val createResponse = client.newCall(createRequest).await()
        val createText = createResponse.body?.string().orEmpty()
        if (!createResponse.isSuccessful) {
            error(
                "Failed to create Volcengine video task: ${createResponse.code} " +
                    (parseVolcengineErrorMessage(createText) ?: createText),
            )
        }

        val taskId = parseVolcengineTaskId(createText)
            ?: error("No id in Volcengine video response: $createText")

        val pollRequest = Request.Builder()
            .url("$base${volcengineVideoTaskPath(taskId)}")
            .addHeader("Authorization", "Bearer $key")
            .build()

        repeat(VOLCENGINE_VIDEO_POLL_ATTEMPTS) {
            delay(VOLCENGINE_VIDEO_POLL_INTERVAL_MS)
            val pollResponse = client.newCall(pollRequest).await()
            val pollText = pollResponse.body?.string().orEmpty()
            if (!pollResponse.isSuccessful) {
                error("Failed to poll Volcengine video task $taskId: ${pollResponse.code} $pollText")
            }
            when (parseVolcengineTaskStatus(pollText)) {
                "succeeded" -> {
                    val url = parseVolcengineVideoUrl(pollText)
                        ?: error("Volcengine video task $taskId succeeded with no video_url: $pollText")
                    return listOf(downloadVideoAsBase64(url))
                }

                "failed", "expired", "canceled", "cancelled" -> error(
                    "Volcengine video task $taskId did not succeed: " +
                        (parseVolcengineErrorMessage(pollText) ?: pollText),
                )

                // queued / running - keep polling.
                else -> Unit
            }
        }
        error("Volcengine video task $taskId timed out after $VOLCENGINE_VIDEO_POLL_ATTEMPTS polls")
    }

    /**
     * MiniMax Hailuo text-to-video / image-to-video: create a task, poll it, then download the
     * clip. The model id picks between two API generations — see [minimaxUsesH3Dialect]: the
     * classic flat `v1` flow (create → query → `files/retrieve`) and the `v2` multimodal-`content[]`
     * H3 flow (create → query, whose answer already carries the download URL).
     */
    private suspend fun generateVideoViaMiniMax(
        providerSetting: ProviderSetting.OpenAI,
        params: VideoGenerationParams,
        key: String,
    ): List<VideoGenerationItem> {
        val root = minimaxNativeRoot(providerSetting.baseUrl)
            ?: error("Not a MiniMax base URL: ${providerSetting.baseUrl}")

        val firstFrame = firstFrameDataUri(params)
        val usesH3 = minimaxUsesH3Dialect(params.model.modelId)
        val createBody = (
            if (usesH3) {
                buildMiniMaxH3VideoRequestBody(
                    model = params.model,
                    prompt = params.prompt,
                    aspectRatio = params.aspectRatio,
                    durationSeconds = params.durationSeconds,
                    firstFrameUrl = firstFrame,
                )
            } else {
                buildMiniMaxVideoRequestBody(
                    model = params.model,
                    prompt = params.prompt,
                    durationSeconds = params.durationSeconds,
                    firstFrameUrl = firstFrame,
                )
            }
            ).mergeCustomBody(params.customBody)

        val createText = executeText(
            request = authorizedJsonRequest(
                url = "$root${if (usesH3) MINIMAX_H3_VIDEO_GENERATION_PATH else MINIMAX_VIDEO_GENERATION_PATH}",
                key = key,
            ).headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                .post(json.encodeToString(createBody).toRequestBody("application/json".toMediaType()))
                .build(),
            label = "create MiniMax video task",
        )
        // MiniMax reports a rejected task inside `base_resp` with HTTP 200 — catch it here or the
        // task id would look legitimate.
        parseMiniMaxBaseRespMessage(createText)?.let { error("MiniMax rejected the video task: $it") }
        val taskId = parseMiniMaxTaskId(createText)
            ?: error("No task_id in MiniMax video response: $createText")

        val videoUrl = if (usesH3) {
            awaitVideoTask(
                label = "MiniMax video task $taskId",
                attempts = MINIMAX_VIDEO_POLL_ATTEMPTS,
                intervalMs = MINIMAX_VIDEO_POLL_INTERVAL_MS,
                pollRequest = { authorizedJsonRequest("$root${minimaxH3QueryPath(taskId)}", key).get().build() },
            ) { pollText ->
                val status = parseMiniMaxH3TaskStatus(pollText)
                when (videoTaskState(status)) {
                    VideoTaskState.SUCCEEDED -> VideoTaskPoll.Done(
                        parseMiniMaxH3VideoUrl(pollText)
                            ?: error("MiniMax task $taskId succeeded with no content.url: $pollText"),
                    )

                    VideoTaskState.FAILED, VideoTaskState.CANCELLED, VideoTaskState.EXPIRED ->
                        VideoTaskPoll.Failed(
                            parseMiniMaxH3ErrorMessage(pollText) ?: videoTaskFailurePhrase(status),
                        )

                    // queued / running - keep polling.
                    VideoTaskState.RUNNING, VideoTaskState.UNKNOWN -> VideoTaskPoll.Pending(status)
                }
            }
        } else {
            awaitVideoTask(
                label = "MiniMax video task $taskId",
                attempts = MINIMAX_VIDEO_POLL_ATTEMPTS,
                intervalMs = MINIMAX_VIDEO_POLL_INTERVAL_MS,
                pollRequest = { authorizedJsonRequest(minimaxVideoQueryUrl(root, taskId), key).get().build() },
            ) { pollText ->
                val status = parseMiniMaxTaskStatus(pollText)
                when (videoTaskState(status)) {
                    VideoTaskState.SUCCEEDED -> {
                        // A second call is unavoidable here: `query` only hands back a file_id.
                        val fileId = parseMiniMaxFileId(pollText)
                            ?: error("MiniMax task $taskId succeeded with no file_id: $pollText")
                        val fileText = executeText(
                            request = authorizedJsonRequest(minimaxFileRetrieveUrl(root, fileId), key)
                                .get()
                                .build(),
                            label = "retrieve MiniMax file $fileId",
                        )
                        VideoTaskPoll.Done(
                            parseMiniMaxDownloadUrl(fileText)
                                ?: error("MiniMax file $fileId has no download.url: $fileText"),
                        )
                    }

                    VideoTaskState.FAILED, VideoTaskState.CANCELLED, VideoTaskState.EXPIRED ->
                        VideoTaskPoll.Failed(
                            parseMiniMaxBaseRespMessage(pollText) ?: videoTaskFailurePhrase(status),
                        )

                    // Preparing / Queueing / Processing - keep polling.
                    VideoTaskState.RUNNING, VideoTaskState.UNKNOWN -> VideoTaskPoll.Pending(status)
                }
            }
        }
        return listOf(downloadVideoAsBase64(videoUrl))
    }

    /**
     * Zhipu (CogVideoX / 清影) text-to-video / image-to-video: submit a `videos/generations` task and
     * poll `async-result/{id}` until `task_status == SUCCESS`. See [ZhipuVideoRequest].
     */
    private suspend fun generateVideoViaZhipu(
        providerSetting: ProviderSetting.OpenAI,
        params: VideoGenerationParams,
        key: String,
    ): List<VideoGenerationItem> {
        val root = zhipuNativeRoot(providerSetting.baseUrl)
            ?: error("Not a Zhipu base URL: ${providerSetting.baseUrl}")

        // Zhipu documents the image field as a URL *or* bare base64, so the data: prefix is dropped.
        val createBody = buildZhipuVideoRequestBody(
            model = params.model,
            prompt = params.prompt,
            aspectRatio = params.aspectRatio,
            durationSeconds = params.durationSeconds,
            firstFrameImage = firstFrameDataUri(params)?.let(::inlineImagePayload),
        ).mergeCustomBody(params.customBody)

        val createText = executeText(
            request = authorizedJsonRequest("$root$ZHIPU_VIDEO_GENERATION_PATH", key)
                .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                .post(json.encodeToString(createBody).toRequestBody("application/json".toMediaType()))
                .build(),
            label = "create Zhipu video task",
        )
        val taskId = parseZhipuTaskId(createText)
            ?: error("No id in Zhipu video response: $createText")

        val videoUrl = awaitVideoTask(
            label = "Zhipu video task $taskId",
            attempts = ZHIPU_VIDEO_POLL_ATTEMPTS,
            intervalMs = ZHIPU_VIDEO_POLL_INTERVAL_MS,
            pollRequest = { authorizedJsonRequest("$root${zhipuAsyncResultPath(taskId)}", key).get().build() },
        ) { pollText ->
            val status = parseZhipuTaskStatus(pollText)
            when (videoTaskState(status)) {
                VideoTaskState.SUCCEEDED -> VideoTaskPoll.Done(
                    parseZhipuVideoUrl(pollText)
                        ?: error("Zhipu task $taskId succeeded with no video_result: $pollText"),
                )

                VideoTaskState.FAILED, VideoTaskState.CANCELLED, VideoTaskState.EXPIRED ->
                    VideoTaskPoll.Failed(
                        parseZhipuErrorMessage(pollText) ?: videoTaskFailurePhrase(status),
                    )

                // PROCESSING - keep polling.
                VideoTaskState.RUNNING, VideoTaskState.UNKNOWN -> VideoTaskPoll.Pending(status)
            }
        }
        return listOf(downloadVideoAsBase64(videoUrl))
    }

    /**
     * SiliconFlow text-to-video / image-to-video: `video/submit` hands back a `requestId`, which
     * `video/status` is polled with (a POST, unlike every other vendor here) until `Succeed`. See
     * [SiliconFlowVideoRequest].
     */
    private suspend fun generateVideoViaSiliconFlow(
        providerSetting: ProviderSetting.OpenAI,
        params: VideoGenerationParams,
        key: String,
    ): List<VideoGenerationItem> {
        val root = siliconFlowNativeRoot(providerSetting.baseUrl)
            ?: error("Not a SiliconFlow base URL: ${providerSetting.baseUrl}")

        // SiliconFlow documents the image field as a URL *or* bare base64, so the data: prefix is dropped.
        val createBody = buildSiliconFlowVideoRequestBody(
            model = params.model,
            prompt = params.prompt,
            aspectRatio = params.aspectRatio,
            firstFrameImage = firstFrameDataUri(params)?.let(::inlineImagePayload),
        ).mergeCustomBody(params.customBody)

        val createText = executeText(
            request = authorizedJsonRequest("$root$SILICONFLOW_VIDEO_SUBMIT_PATH", key)
                .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                .post(json.encodeToString(createBody).toRequestBody("application/json".toMediaType()))
                .build(),
            label = "submit SiliconFlow video task",
        )
        val requestId = parseSiliconFlowRequestId(createText)
            ?: error("No requestId in SiliconFlow video response: $createText")
        val statusBody = buildSiliconFlowStatusRequestBody(requestId)

        val videoUrl = awaitVideoTask(
            label = "SiliconFlow video request $requestId",
            attempts = SILICONFLOW_VIDEO_POLL_ATTEMPTS,
            intervalMs = SILICONFLOW_VIDEO_POLL_INTERVAL_MS,
            pollRequest = {
                authorizedJsonRequest("$root$SILICONFLOW_VIDEO_STATUS_PATH", key)
                    .post(json.encodeToString(statusBody).toRequestBody("application/json".toMediaType()))
                    .build()
            },
        ) { pollText ->
            val status = parseSiliconFlowStatus(pollText)
            when (videoTaskState(status)) {
                VideoTaskState.SUCCEEDED -> VideoTaskPoll.Done(
                    parseSiliconFlowVideoUrl(pollText)
                        ?: error("SiliconFlow request $requestId succeeded with no video URL: $pollText"),
                )

                VideoTaskState.FAILED, VideoTaskState.CANCELLED, VideoTaskState.EXPIRED ->
                    VideoTaskPoll.Failed(
                        parseSiliconFlowErrorMessage(pollText) ?: videoTaskFailurePhrase(status),
                    )

                // InQueue / InProgress - keep polling.
                VideoTaskState.RUNNING, VideoTaskState.UNKNOWN -> VideoTaskPoll.Pending(status)
            }
        }
        return listOf(downloadVideoAsBase64(videoUrl))
    }

    /**
     * Polls an async video task until it reaches a terminal state, returning the finished clip's
     * URL. [onPoll] classifies one poll body and may itself perform IO — MiniMax's classic API needs
     * a second call to turn a `file_id` into a download URL.
     */
    private suspend fun awaitVideoTask(
        label: String,
        attempts: Int,
        intervalMs: Long,
        pollRequest: () -> Request,
        onPoll: suspend (String) -> VideoTaskPoll,
    ): String {
        var lastStatus: String? = null
        repeat(attempts) {
            delay(intervalMs)
            val text = executeText(pollRequest(), "poll $label")
            when (val poll = onPoll(text)) {
                is VideoTaskPoll.Done -> return poll.videoUrl
                is VideoTaskPoll.Failed -> error("$label did not succeed: ${poll.message ?: text}")
                is VideoTaskPoll.Pending -> lastStatus = poll.status
            }
        }
        // Name the last status we saw: an unmapped word would otherwise cost another 9-10 minutes
        // to diagnose as a bare timeout.
        error("$label timed out after $attempts polls (last status: ${lastStatus ?: "not reported"})")
    }

    /** One poll's verdict for [awaitVideoTask]. */
    private sealed interface VideoTaskPoll {
        /** The task finished; [videoUrl] is the still-fresh download URL. */
        data class Done(val videoUrl: String) : VideoTaskPoll

        /** The task failed / expired / was cancelled; [message] is the vendor's explanation. */
        data class Failed(val message: String?) : VideoTaskPoll

        /**
         * The task is still queued or running — keep polling. [status] is the raw word just seen,
         * so a timeout can report what the vendor kept saying.
         */
        data class Pending(val status: String?) : VideoTaskPoll
    }

    /** Runs [request] and returns its body, turning a non-2xx answer into a labelled error. */
    private suspend fun executeText(request: Request, label: String): String {
        val response = client.newCall(request).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("Failed to $label: ${response.code} $text")
        }
        return text
    }

    /** A bearer-authenticated JSON `Request.Builder` for the native video APIs above. */
    private fun authorizedJsonRequest(url: String, key: String): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $key")
        .addHeader("Content-Type", "application/json")

    /**
     * Downloads a finished clip and hands it back base64-encoded, mirroring
     * [downloadImageAsBase64]. Both vendors expire their result URLs (Ark: 24 h), so fetching here
     * — while the URL is guaranteed fresh — is the whole point.
     */
    private suspend fun downloadVideoAsBase64(url: String): VideoGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated video: ${response.code}")
        }

        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "video/mp4"
        val base64 = Base64.encode(body.bytes())

        return VideoGenerationItem(
            data = base64,
            mimeType = mimeType,
        )
    }

    /**
     * DashScope (Aliyun Bailian) image editing. The OpenAI-compatible base URL this provider
     * normally uses has no `/images/edits` (it 404s, exactly like `/images/generations`), so the
     * source images go to one of the provider's native edit endpoints instead. Which one is picked
     * from the model id and, like [generateImageViaDashScope], retried on the other when the
     * gateway answers the "model does not match this endpoint" rejection:
     *
     *  - **Multimodal** (`qwen-image-edit*`, `qwen-image-2.x` / `3.x`, `wan2.6-image`): the
     *    synchronous `multimodal-generation/generation` endpoint, whose one user message carries
     *    the source image(s) and the instruction as content parts.
     *  - **Legacy** (`wanx*imageedit`): the asynchronous `image2image/image-synthesis` task API
     *    with `input.function = description_edit` and `input.base_image_url`.
     *
     * Source images are read as `data:{mime};base64,...` URIs, which DashScope accepts wherever it
     * accepts a public URL, so no upload step is needed. `size` is not sent - the edit output
     * follows the source image's aspect (see [buildDashScopeMultimodalEditRequestBody]) - and `n`
     * is, so one call yields several images rather than N sequential calls.
     */
    private suspend fun editImageViaDashScope(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageEditParams,
        key: String,
    ): List<ImageGenerationItem> {
        val root = dashScopeNativeRoot(providerSetting.baseUrl)
            ?: error("Not a DashScope base URL: ${providerSetting.baseUrl}")

        val imageDataUris = params.images.map { path ->
            val file = File(path)
            require(file.exists()) { "Image file does not exist: $path" }
            file.toDataUri()
        }
        require(imageDataUris.isNotEmpty()) { "At least one image is required" }

        val preferredMultimodal = dashScopePrefersMultimodalEdit(params.model.modelId)
        val first = runCatching {
            submitDashScopeEdit(root, providerSetting, params, key, imageDataUris, preferredMultimodal)
        }
        val failure = first.exceptionOrNull()
        if (failure == null) return first.getOrThrow()
        // Never swallow coroutine cancellation as a provider failure.
        if (failure is CancellationException) throw failure

        // A `url error` rejection means the model and the endpoint disagree about the body shape.
        // Retry once on the other endpoint so a model we did not enumerate still works.
        if (failure is DashScopeSubmitRejected && failure.endpointMismatch) {
            return submitDashScopeEdit(root, providerSetting, params, key, imageDataUris, !preferredMultimodal)
        }
        throw failure
    }

    /**
     * Runs one DashScope image-edit request against the shape selected by [multimodal] (sync
     * messages body carrying the images) or its opposite (legacy `description_edit` async task),
     * and returns the downloaded images.
     */
    private suspend fun submitDashScopeEdit(
        root: String,
        providerSetting: ProviderSetting.OpenAI,
        params: ImageEditParams,
        key: String,
        imageDataUris: List<String>,
        multimodal: Boolean,
    ): List<ImageGenerationItem> {
        val (path, body) = if (multimodal) {
            DASHSCOPE_MULTIMODAL_GENERATION_PATH to buildDashScopeMultimodalEditRequestBody(
                model = params.model,
                prompt = params.prompt,
                imageDataUris = imageDataUris,
                numOfImages = params.numOfImages,
            )
        } else {
            DASHSCOPE_IMAGE_EDIT_SYNTHESIS_PATH to buildDashScopeEditRequestBody(
                model = params.model,
                prompt = params.prompt,
                baseImageUri = imageDataUris.first(),
                numOfImages = params.numOfImages,
            )
        }
        val submitBody = body.mergeCustomBody(params.customBody)

        val submitRequest = Request.Builder()
            .url("$root$path")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .apply { if (!multimodal) addHeader("X-DashScope-Async", "enable") }
            .post(json.encodeToString(submitBody).toRequestBody("application/json".toMediaType()))
            .build()

        val submitResponse = client.newCall(submitRequest).await()
        val submitText = submitResponse.body?.string().orEmpty()
        if (!submitResponse.isSuccessful) {
            throw DashScopeSubmitRejected(
                message = "Failed to submit image edit: ${submitResponse.code} $submitText",
                endpointMismatch = isDashScopeEndpointModelMismatch(submitText),
            )
        }

        // Multimodal: the synchronous response already carries the edited images.
        if (multimodal) {
            val urls = parseDashScopeMultimodalImageUrls(submitText)
            if (urls.isEmpty()) {
                error("DashScope returned no edited images for ${params.model.modelId}: $submitText")
            }
            return urls.map { downloadImageAsBase64(it) }
        }

        // Legacy: the edit is an async task, polled exactly like text-to-image.
        val taskId = parseDashScopeTaskId(submitText)
            ?: error("No task_id in DashScope edit response: $submitText")
        return pollDashScopeImageTask(root, key, taskId)
    }

    private suspend fun parseImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
        val data = body["data"]?.jsonArray ?: error("No data in image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                ImageGenerationItem(
                    data = b64Json,
                    mimeType = outputFormat.toImageMimeType(),
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in image response")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated image: ${response.code} ${response.body.string()}")
        }

        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "image/png"
        val base64 = Base64.encode(body.bytes())

        return ImageGenerationItem(
            data = base64,
            mimeType = mimeType
        )
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun File.toDataUri(): String = "data:${imageMediaType()};base64,${Base64.encode(readBytes())}"

    private fun String.toImageMimeType(): String = when (lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
