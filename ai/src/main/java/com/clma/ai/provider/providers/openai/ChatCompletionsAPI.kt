package com.clma.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.clma.ai.core.InputSchema
import com.clma.ai.core.MessageRole
import com.clma.ai.core.ReasoningLevel
import com.clma.ai.core.TokenUsage
import com.clma.ai.provider.Modality
import com.clma.ai.provider.Model
import com.clma.ai.provider.ModelAbility
import com.clma.ai.provider.ProviderSetting
import com.clma.ai.provider.TextGenerationResult
import com.clma.ai.provider.TextGenerationParams
import com.clma.ai.provider.stream.SseEvent
import com.clma.ai.provider.providers.PartGroup
import com.clma.ai.provider.providers.groupPartsByToolBoundary
import com.clma.ai.provider.stream.TextToolCallParser
import com.clma.ai.registry.ModelRegistry
import com.clma.ai.ui.StreamChunk
import com.clma.ai.ui.OpenRouterReasoningMetadata
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessageAnnotation
import com.clma.ai.ui.UIMessagePart
import com.clma.ai.ui.metadataAs
import com.clma.ai.ui.toMetadata
import com.clma.ai.util.HttpException
import com.clma.ai.util.KeyRoulette
import com.clma.ai.util.configureReferHeaders
import com.clma.ai.util.configureSessionHeaders
import com.clma.ai.util.encodeBase64
import com.clma.ai.util.json
import com.clma.ai.util.mergeCustomBody
import com.clma.ai.util.mergeCustomHeaders
import com.clma.ai.util.parseErrorDetail
import com.clma.ai.util.redactSecrets
import com.clma.ai.util.stringSafe
import com.clma.ai.util.toHeaders
import com.clma.common.android.Logging
import com.clma.common.http.await
import com.clma.common.http.jsonArrayOrNull
import com.clma.common.http.jsonObjectOrNull
import com.clma.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import com.clma.ai.util.toJsonRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ChatCompletionsAPI"

// Same wording toToolResultContent() already uses when downgrading a tool-result image to
// text for a model without image input support; reused here for consistency.
private const val IMAGE_UNSUPPORTED_PLACEHOLDER =
    "[Image output omitted: current model does not support image input]"

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val requestBody =
            buildChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting
            )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .post(requestBody.toJsonRequestBody())
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeaders(providerSetting.baseUrl, params.sessionId)
            .build()

        if (Logging.isDebugLoggingEnabled()) {
            Log.i(TAG, "generateText: ${json.encodeToString(redactSecrets(requestBody))}")
        }

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val choice = bodyJson["choices"]?.jsonArray?.get(0)?.jsonObject ?: error("choices is null")

        val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
        val finishReason = choice["finish_reason"]
            ?.jsonPrimitive
            ?.content
            ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

        // Same recovery as the streaming decoder: a model that writes its tool call as text would
        // otherwise silently lose it for assistants with streaming turned off. Applied after
        // parsing so `parseMessage` keeps taking just the payload.
        val textToolParser = if (
            params.textToolCallParsing && params.model.abilities.contains(ModelAbility.TOOL)
        ) {
            TextToolCallParser(params.tools)
        } else {
            null
        }
        val parsedMessage = parseMessage(message).let { msg ->
            if (textToolParser == null) {
                msg
            } else {
                msg.copy(
                    parts = msg.parts.flatMap { part ->
                        if (part is UIMessagePart.Text) {
                            textToolParser.feed(part.text) + textToolParser.flushPending()
                        } else {
                            listOf(part)
                        }
                    },
                )
            }
        }

        TextGenerationResult(
            id = id,
            model = model,
            message = parsedMessage,
            finishReason = if (textToolParser?.consumePendingFinishReason() != null) {
                "tool_calls"
            } else {
                finishReason
            },
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildChatCompletionRequest(
            messages = messages,
            params = params,
            providerSetting = providerSetting,
            stream = true,
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .post(requestBody.toJsonRequestBody())
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeaders(providerSetting.baseUrl, params.sessionId)
            .build()

        if (Logging.isDebugLoggingEnabled()) {
            Log.i(TAG, "streamText: ${json.encodeToString(redactSecrets(requestBody))}")
        }

        // just for debugging response body
        // println(client.newCall(request).await().body.string())

        // Tools handed to the decoder so a text-emitted tool call can be told apart from prose and
        // checked against what this request actually offered. Empty when the model has no tool
        // support, which also switches the fallback off.
        val decoder = ChatCompletionsStreamDecoder(
            tools = if (params.model.abilities.contains(ModelAbility.TOOL)) params.tools else emptyList(),
            textToolCallsEnabled = params.textToolCallParsing,
        )

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: $data")
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: HttpException) {
                    close(e)
                } catch (e: Throwable) {
                    // A single malformed/unparseable line must not escape this
                    // callback: an uncaught exception here propagates through
                    // OkHttp's SSE reader and aborts the whole stream instead of
                    // just skipping this one line.
                    Log.w(TAG, "onEvent: skipping malformed chunk (${e.message})", e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                Log.w(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message} / $response", t)

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        Log.d(TAG, "onFailure: error body $bodyElement")
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw", e)
                    exception = e
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                sendChunks(decoder.onClosed())
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val isOpenRouter = host == "openrouter.ai"
        // OpenRouter prompt caching: add per-block cache_control breakpoints for every model.
        // Anthropic/Gemini/Qwen need them explicitly; providers that cache automatically
        // (OpenAI/DeepSeek/Grok/MiniMax) have the field stripped by OpenRouter. So this is safe
        // for any model and, unlike top-level cache_control, never pins routing to one upstream.
        val openRouterCache = isOpenRouter && providerSetting.promptCaching
        val messagesArray = buildMessages(
            messages = messages,
            includeHistoryReasoning = providerSetting.includeHistoryReasoning,
            openRouterCache = openRouterCache,
            includeOpenRouterReasoningDetails = isOpenRouter,
            supportInputModalities = params.model.inputModalities,
        ).let {
            if (openRouterCache) insertOpenRouterCacheControl(it) else it
        }
        return buildJsonObject {
            put("model", params.model.modelId)
            put("messages", messagesArray)

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_tokens", params.maxTokens)

            put("stream", stream)
            if (stream) {
                if (host != "api.mistral.ai") { // mistral 不支持 stream_options
                    put("stream_options", buildJsonObject {
                        put("include_usage", true)
                    })
                }
            }

            // open router适配
            if(isOpenRouter) {
                // Ask OpenRouter to report the real generation cost in the usage object
                // (surfaced per-message in the UI). Works for both streamed and non-streamed.
                put("usage", buildJsonObject {
                    put("include", true)
                })
                params.sessionId?.let { put("session_id", it) }
                if(params.model.outputModalities.contains(Modality.IMAGE)) {
                    put("modalities", buildJsonArray {
                        add("image")
                        add("text")
                    })
                }
                // Provider routing preferences (sort/order/only/ignore/max_price/...).
                // Forces require_parameters when the request carries tools so a provider
                // that can't do tool-calling isn't picked and silently drops them.
                val hasTools = params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
                buildProviderObject(providerSetting.routing, hasToolsOrSchema = hasTools)?.let {
                    put("provider", it)
                }
                // Fallback models: tried in order when the primary `model` is down,
                // rate-limited, or refuses on moderation.
                buildFallbackModelsArray(params.model.modelId, providerSetting.routing)?.let {
                    put("models", it)
                }
            }

            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                when (host) {
                    "openrouter.ai" -> {
                        // https://openrouter.ai/docs/use-cases/reasoning-tokens
                        put("reasoning", buildJsonObject {
                            when (level) {
                                ReasoningLevel.OFF -> put("effort", "none")
                                ReasoningLevel.AUTO -> put("enabled", true)
                                else -> put("effort", level.effort)
                            }
                        })
                    }

                    "dashscope.aliyuncs.com" -> {
                        // 阿里云百炼
                        // https://bailian.console.aliyun.com/console?tab=doc#/doc/?type=model&url=https%3A%2F%2Fhelp.aliyun.com%2Fdocument_detail%2F2870973.html&renderType=iframe
                        put("enable_thinking", level.isEnabled)
                        if (level != ReasoningLevel.AUTO) put("thinking_budget", level.budgetTokens)
                    }

                    "ark.cn-beijing.volces.com" -> {
                        // 豆包 (火山)
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.mistral.ai" -> {
                        // Mistral 不支持
                    }

                    "chat.intern-ai.org.cn" -> {
                        // 书生
                        // https://internlm.intern-ai.org.cn/api/document?lang=zh
                        put("thinking_mode", level.isEnabled)
                    }

                    "api.siliconflow.cn" -> {
                        // https://docs.siliconflow.cn/cn/userguide/capabilities/reasoning#3-1-api-%E5%8F%82%E6%95%B0
                        val modelId = params.model.modelId
                        val siliconflowThinkingModels = setOf(
                            "Pro/moonshotai/Kimi-K2.5",
                            "Pro/zai-org/GLM-5",
                            "Pro/zai-org/GLM-5.1",
                            "Pro/zai-org/GLM-4.7",
                            "deepseek-ai/DeepSeek-V3.2",
                            "Pro/deepseek-ai/DeepSeek-V3.2",
                            "Qwen/Qwen3.5-397B-A17B",
                            "Qwen/Qwen3.5-122B-A10B",
                            "Qwen/Qwen3.5-35B-A3B",
                            "Qwen/Qwen3.5-27B",
                            "Qwen/Qwen3.5-9B",
                            "Qwen/Qwen3.5-4B",
                            "zai-org/GLM-4.6",
                            "Qwen/Qwen3-8B",
                            "Qwen/Qwen3-14B",
                            "Qwen/Qwen3-32B",
                            "Qwen/Qwen3-30B-A3B",
                            "tencent/Hunyuan-A13B-Instruct",
                            "zai-org/GLM-4.5V",
                            "deepseek-ai/DeepSeek-V3.1-Terminus",
                            "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
                            "deepseek-ai/DeepSeek-V4-Flash",
                            "Pro/deepseek-ai/DeepSeek-V4-Flash",
                            "deepseek-ai/DeepSeek-V4-Pro",
                            "Pro/deepseek-ai/DeepSeek-V4-Pro",
                        )
                        if (modelId in siliconflowThinkingModels) {
                            put("enable_thinking", level.isEnabled)
                        }
                    }

                    "aiping.cn" -> {
                        put("enable_thinking", level.isEnabled)
                    }

                    "open.bigmodel.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com" -> {
                        // 小米 MiMo
                        // https://mimo.mi.com/docs/zh-CN/api/chat/openai-api
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.moonshot.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                            // K2.6 的 thinking.keep 默认为 null（忽略历史思考），思考开启时
                            // 需显式传 "all" 才是保留式思考；文档推荐与 enabled 搭配（#1586）
                            if (level.isEnabled && ModelRegistry.KIMI_K2_6.match(params.model.modelId)) {
                                put("keep", "all")
                            }
                        })
                    }

                    "api.deepseek.com" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                        if (level.isEnabled && level != ReasoningLevel.AUTO) {
                            val effort = when (level) {
                                ReasoningLevel.MEDIUM, ReasoningLevel.HIGH -> "high"
                                ReasoningLevel.MAX -> "max"
                                else -> level.effort
                            }
                            put("reasoning_effort", effort)
                        }
                    }

                    "integrate.api.nvidia.com" -> {
                        if ("deepseek-v4" in params.model.modelId.lowercase()) {
                            if (level != ReasoningLevel.AUTO) {
                                val effort = when (level) {
                                    ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
                                    ReasoningLevel.OFF -> "none"
                                    else -> "high"
                                }
                                put("reasoning_effort", effort)
                            }
                        } else {
                            if (level != ReasoningLevel.AUTO) {
                                put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                            }
                        }
                    }

                    "opencode.ai" -> {
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }

                    else -> {
                        // OpenAI 官方
                        // 文档中，completions API 只支持 "low", "medium", "high"
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                        }
                    }
                }
            }

            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                            ?: InputSchema.Obj(properties = JsonObject(emptyMap()))
                                    )
                                )
                            })
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    // Mirrors the native ClaudeProvider's breakpoint placement, but in OpenAI message
    // shape: mark the static system prefix and the cacheable conversation prefix (the
    // second-to-last user turn). Two of OpenRouter's four allowed breakpoints.
    private fun insertOpenRouterCacheControl(messages: JsonArray): JsonArray {
        val systemIndex = messages.indexOfLast {
            it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "system"
        }
        val userIndices = messages.mapIndexedNotNull { index, msg ->
            if (msg.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "user") index else null
        }
        val userTarget = if (userIndices.size >= 2) userIndices[userIndices.size - 2] else -1

        if (systemIndex < 0 && userTarget < 0) return messages

        return JsonArray(messages.mapIndexed { index, msg ->
            when (index) {
                // System: breakpoint on the FIRST (stable) block so the volatile block
                // (memory / recent chats) after it does not invalidate the cached prefix.
                systemIndex -> msg.jsonObject.withCacheControlOnFirstBlock()
                userTarget -> msg.jsonObject.withCacheControlOnLastBlock()
                else -> msg
            }
        })
    }

    private fun JsonObject.withCacheControlOnFirstBlock(): JsonObject {
        val ephemeral = buildJsonObject { put("type", "ephemeral") }
        val newContent: JsonArray = when (val content = this["content"]) {
            is JsonPrimitive -> buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content.contentOrNull ?: "")
                    put("cache_control", ephemeral)
                })
            }

            is JsonArray -> {
                if (content.isEmpty()) return this
                JsonArray(content.mapIndexed { idx, block ->
                    if (idx == 0 && block is JsonObject) {
                        JsonObject(block + ("cache_control" to ephemeral))
                    } else block
                })
            }

            else -> return this
        }
        return JsonObject(this + ("content" to newContent))
    }

    // Attach cache_control to the last content block, promoting a plain-string content
    // to the array form that can carry the field.
    private fun JsonObject.withCacheControlOnLastBlock(): JsonObject {
        val ephemeral = buildJsonObject { put("type", "ephemeral") }
        val newContent: JsonArray = when (val content = this["content"]) {
            is JsonPrimitive -> buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content.contentOrNull ?: "")
                    put("cache_control", ephemeral)
                })
            }

            is JsonArray -> {
                if (content.isEmpty()) return this
                JsonArray(content.mapIndexed { idx, block ->
                    if (idx == content.lastIndex && block is JsonObject) {
                        JsonObject(block + ("cache_control" to ephemeral))
                    } else block
                })
            }

            else -> return this
        }
        return JsonObject(this + ("content" to newContent))
    }

    private fun isModelAllowTemperature(model: Model): Boolean {
        val isMoonshotRestricted = ModelRegistry.KIMI_K2_5.match(model.modelId) ||
                ModelRegistry.KIMI_K2_6.match(model.modelId) ||
                ModelRegistry.KIMI_K3.match(model.modelId) ||
                ModelRegistry.KIMI_K3_ALIAS.match(model.modelId)
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) &&
               !ModelRegistry.GPT_5.match(model.modelId) &&
               !isMoonshotRestricted
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        includeHistoryReasoning: Boolean = true,
        openRouterCache: Boolean = false,
        includeOpenRouterReasoningDetails: Boolean = false,
        supportInputModalities: List<Modality> = listOf(Modality.TEXT, Modality.IMAGE),
    ) = buildJsonArray {
        val filteredMessages = messages.filter { it.isValidToUpload() }

        filteredMessages.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(
                    message = message,
                    includeReasoning = includeHistoryReasoning,
                    includeOpenRouterReasoningDetails = includeOpenRouterReasoningDetails,
                    supportInputModalities = supportInputModalities,
                )
            } else {
                addNonAssistantMessage(
                    message,
                    openRouterCache = openRouterCache,
                    supportInputModalities = supportInputModalities,
                )
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(
        message: UIMessage,
        includeReasoning: Boolean,
        includeOpenRouterReasoningDetails: Boolean,
        supportInputModalities: List<Modality>,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        var reasoningPart: UIMessagePart.Reasoning? = null

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    // 从当前 group 中提取 reasoning（保持顺序）
                    if (includeReasoning) {
                        group.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull()?.let {
                            reasoningPart = it
                        }
                    }
                    group.parts
                        .filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
                        .forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 输出 assistant 消息（包含累积的内容 + tool_calls）
                    buildAssistantMessageJson(
                        contentParts = contentBuffer,
                        tools = group.tools,
                        reasoningPart = reasoningPart,
                        supportInputModalities = supportInputModalities,
                        includeOpenRouterReasoningDetails = includeOpenRouterReasoningDetails,
                    )?.let { assistantMessage ->
                        add(assistantMessage)
                    }
                    contentBuffer.clear()
                    reasoningPart = null // 清空，下一个 group 可能有新的 reasoning

                    // 紧跟 tool 结果消息: 同一批工具调用的结果必须全部连续输出, 中间不能
                    // 插入任何其他消息, 否则部分 provider 会报 "no tool output found for
                    // tool call" (issue #104). ChatCompletions 的 role:"tool" 内容必须始终
                    // 是纯字符串, 图片改为在本批全部结果之后统一以一条 role:"user" 消息带出。
                    val liftedImages = mutableListOf<Pair<UIMessagePart.Tool, UIMessagePart.Image>>()
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tool.toolCallId)
                            put("content", tool.toToolResultContent(supportInputModalities))
                        })
                        if (Modality.IMAGE in supportInputModalities) {
                            tool.output.filterIsInstance<UIMessagePart.Image>().forEach { image ->
                                liftedImages.add(tool to image)
                            }
                        }
                    }
                    // Image lift: ChatCompletions tool messages are text-only, so any
                    // UIMessagePart.Image returned by a tool would be invisible to a
                    // vision-capable model otherwise. Emit a single follow-up user message,
                    // after every tool result of this batch, that carries those images so
                    // the model actually sees them on its next turn (e.g. take_screenshot,
                    // take_photo, etc.), while keeping tool_call/tool_result pairing intact.
                    if (liftedImages.isNotEmpty()) {
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                liftedImages.forEach { (tool, image) ->
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", "[Tool ${tool.toolName} produced the image below.]")
                                    })
                                    add(buildJsonObject {
                                        image.encodeBase64().onSuccess { encodedImage ->
                                            put("type", "image_url")
                                            put("image_url", buildJsonObject {
                                                put("url", encodedImage.base64)
                                            })
                                        }.onFailure {
                                            put("type", "text")
                                            put("text", "(image encode failed: ${it.message})")
                                        }
                                    })
                                }
                            }
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty() || reasoningPart != null) {
            buildAssistantMessageJson(
                contentParts = contentBuffer,
                tools = emptyList(),
                reasoningPart = reasoningPart,
                supportInputModalities = supportInputModalities,
                includeOpenRouterReasoningDetails = includeOpenRouterReasoningDetails,
            )?.let { assistantMessage ->
                add(assistantMessage)
            }
        }
    }

    private fun buildAssistantMessageJson(
        contentParts: List<UIMessagePart>,
        tools: List<UIMessagePart.Tool>,
        reasoningPart: UIMessagePart.Reasoning?,
        supportInputModalities: List<Modality>,
        includeOpenRouterReasoningDetails: Boolean,
    ): JsonObject? {
        val hasUsableContent = contentParts.any { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.isNotBlank()
                is UIMessagePart.Image -> part.url.isNotBlank()
                else -> false
            }
        }
        val reasoningDetails = if (includeOpenRouterReasoningDetails) {
            reasoningPart?.metadataAs<OpenRouterReasoningMetadata>()?.reasoningDetails
        } else {
            null
        }
        val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank() || !reasoningDetails.isNullOrEmpty()
        if (!hasUsableContent && !hasReasoning && tools.isEmpty()) {
            return null
        }

        return buildJsonObject {
            put("role", "assistant")

            // reasoning_content
            if (hasReasoning) {
                if (!reasoningDetails.isNullOrEmpty()) {
                    put("reasoning_details", reasoningDetails)
                } else {
                    put("reasoning_content", reasoningPart?.reasoning.orEmpty())
                }
            }

            // content
            if (contentParts.isEmpty()) {
                put("content", "")
            } else if (contentParts.size == 1 && contentParts[0] is UIMessagePart.Text) {
                put("content", (contentParts[0] as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    contentParts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    if (Modality.IMAGE !in supportInputModalities) {
                                        put("type", "text")
                                        put("text", IMAGE_UNSUPPORTED_PLACEHOLDER)
                                    } else {
                                        part.encodeBase64().onSuccess { encodedImage ->
                                            put("type", "image_url")
                                            put("image_url", buildJsonObject {
                                                put("url", encodedImage.base64)
                                            })
                                        }.onFailure {
                                            Log.w(TAG, "failed to encode image to base64", it)
                                            put("type", "text")
                                            put("text", "")
                                        }
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }

            // tool_calls
            if (tools.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("id", tool.toolCallId)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.toolName)
                                // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送。
                                // redactSecrets: password / private_key / token 等不得回灌给云端模型。
                                put("arguments", com.clma.ai.util.redactSecrets(tool.inputAsJson()).toString())
                            })
                        })
                    }
                })
            }
        }
    }

    private fun JsonArrayBuilder.addNonAssistantMessage(
        message: UIMessage,
        openRouterCache: Boolean = false,
        supportInputModalities: List<Modality>,
    ) {
        add(buildJsonObject {
            put("role", JsonPrimitive(message.role.name.lowercase()))

            val textParts = message.parts.filterIsInstance<UIMessagePart.Text>()
            val allText = message.parts.isNotEmpty() && message.parts.all { it is UIMessagePart.Text }

            if (message.role == MessageRole.SYSTEM && allText && openRouterCache && textParts.size >= 2) {
                // Emit the system prompt as content blocks [stable, volatile] so the
                // cache_control breakpoint can land on the stable (first) block; the
                // volatile block after it does not bust the prefix hash.
                putJsonArray("content") {
                    // Append "\n" to every block but the last so the concatenated block
                    // text equals the "\n"-joined non-caching form below (stable\nvolatile
                    // either way); otherwise cache-on and cache-off send different prompts.
                    textParts.forEachIndexed { index, part ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", if (index == textParts.lastIndex) part.text else "${part.text}\n")
                        })
                    }
                }
            } else if (message.role == MessageRole.SYSTEM && allText) {
                // Non-caching path: join the stable+volatile parts into a single string,
                // byte-identical to the combined system prompt other providers received.
                put("content", textParts.joinToString("\n") { it.text })
            } else if (message.parts.isOnlyTextPart()) {
                put("content", message.parts.filterIsInstance<UIMessagePart.Text>().first().text)
            } else {
                putJsonArray("content") {
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    if (Modality.IMAGE !in supportInputModalities) {
                                        put("type", "text")
                                        put("text", IMAGE_UNSUPPORTED_PLACEHOLDER)
                                    } else {
                                        part.encodeBase64().onSuccess { encodedImage ->
                                            put("type", "image_url")
                                            put("image_url", buildJsonObject {
                                                put("url", encodedImage.base64)
                                            })
                                        }.onFailure {
                                            Log.w(TAG, "failed to encode image to base64", it)
                                            put("type", "text")
                                            put("text", "")
                                        }
                                    }
                                })
                            }

                            // D3 — audio goes out as DashScope's input_audio block, video as
                            // video_url; a model that does not declare the modality gets a named
                            // placeholder instead of a silent drop (see OpenAIMultimodalParts).
                            is UIMessagePart.Audio -> {
                                add(
                                    OpenAIMultimodalParts.audioContentPart(
                                        supportsAudioInput = Modality.AUDIO in supportInputModalities,
                                        url = part.url,
                                        encode = { part.encodeBase64(withPrefix = false).getOrNull() },
                                    )
                                )
                            }

                            is UIMessagePart.Video -> {
                                add(
                                    OpenAIMultimodalParts.videoContentPart(
                                        supportsVideoInput = Modality.VIDEO in supportInputModalities,
                                        url = part.url,
                                        encode = { part.encodeBase64(withPrefix = false).getOrNull() },
                                    )
                                )
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    private fun UIMessagePart.Tool.toToolResultContent(supportInputModalities: List<Modality>): JsonElement {
        // ChatCompletions role:"tool" content must always be a plain string (issue #104):
        // providers reject array content there. Images go out separately as a lifted
        // role:"user" message (see addAssistantMessages), so here they only leave a
        // truthful placeholder pointing at that follow-up message.
        val supportsImageInput = Modality.IMAGE in supportInputModalities
        return JsonPrimitive(output.mapNotNull { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Image -> if (supportsImageInput) {
                    "[Image output attached in the following user message]"
                } else {
                    "[Image output omitted: current model does not support image input]"
                }
                else -> null
            }
        }.joinToString("\n"))
    }

    private fun parseMessage(jsonObject: JsonObject): UIMessage {
        val role = MessageRole.valueOf(
            jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )

        // 也许支持其他模态的输出content?
        val contentElement = jsonObject["content"]
        // content可能是字符串, 也可能是block数组(如[{type:"text",text:"..."}]); 数组时拼接text块, 否则文本会丢失
        val content = contentElement?.jsonPrimitiveOrNull?.contentOrNull
            ?: (contentElement as? JsonArray)?.mapNotNull { block ->
                val obj = block.jsonObjectOrNull ?: return@mapNotNull null
                if (obj["type"]?.jsonPrimitiveOrNull?.contentOrNull == "text") {
                    obj["text"]?.jsonPrimitiveOrNull?.contentOrNull
                } else {
                    null
                }
            }?.joinToString("") ?: ""
        val reasoning = jsonObject["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["content"]?.takeIf { it is JsonArray }?.let { arr ->
                // Mistral接口
                // {"id":"","object":"chat.completion.chunk","created":1772351733,"model":"magistral-medium-2509","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"好的"}]}]},"finish_reason":null}]}
                arr.jsonArrayOrNull?.getOrNull(0)?.jsonObject?.get("thinking")?.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull?.get(
                    "text"
                )?.jsonPrimitiveOrNull?.contentOrNull
            }
        val reasoningMetadata = jsonObject["reasoning_details"]?.jsonArrayOrNull?.let {
            OpenRouterReasoningMetadata(reasoningDetails = it).toMetadata()
        }
        val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
        val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty() || reasoningMetadata != null) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning.orEmpty(),
                            createdAt = Clock.System.now(),
                            finishedAt = null,
                            metadata = reasoningMetadata,
                        )
                    )
                }
                toolCalls.forEach { toolCalls ->
                    val type = toolCalls.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    if (!type.isNullOrEmpty() && type != "function") {
                        // Skip unsupported tool-call types rather than throwing, which would
                        // crash the stream. Today only "function" is handled.
                        Log.w(TAG, "skipping unsupported tool call type: $type")
                        return@forEach
                    }
                    val toolCallId = toolCalls.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    val toolName =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val arguments =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                    add(
                        UIMessagePart.Tool(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            input = arguments ?: "",
                            output = emptyList()
                        )
                    )
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    val type = imageObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (type != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    // OpenRouter image models return data:image/<mime>;base64,... for any mime
                    // (png/jpeg/webp). Parse mime-agnostically; the old png-hardcoded substring
                    // returned garbage for non-png images.
                    val parsed = parseImageDataUri(url) ?: return@forEach
                    add(UIMessagePart.Image(parsed.base64))
                }
            },
            annotations = parseAnnotations(
                jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                    emptyList()
                )
            ),
        )
    }

    private fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.mapNotNull { element ->
            val type =
                element.jsonObject["type"]?.jsonPrimitive?.contentOrNull
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> {
                    // Newer providers add annotation types (file_citation, web_search_result,
                    // ...). Skip unknown/missing types instead of throwing, which would crash
                    // the whole stream for the user.
                    Log.w(TAG, "skipping unknown annotation type: $type")
                    null
                }
            }
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        // P2-11a: pull the hit count out first so that we can tell a reported zero apart
        // from a provider that simply does not report cache fields at all.
        // 各 provider 汇报缓存命中的字段形状不统一，按方言兜底解析（#1576）：
        // OpenAI 嵌套 -> Moonshot 顶层 cached_tokens -> DeepSeek prompt_cache_hit_tokens
        val cacheHitTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
            ?: jsonObject["cached_tokens"]?.jsonPrimitive?.intOrNull
            ?: jsonObject["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
        return TokenUsage(
            promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = cacheHitTokens ?: 0,
            // P2-11a: unreported is not the same as a reported zero.
            cachedTokensReported = cacheHitTokens != null,
            cacheMissTokens = jsonObject["prompt_cache_miss_tokens"]?.jsonPrimitive?.intOrNull,
            // Optional OpenAI-style detail block (absent for most providers -> null).
            reasoningTokens = jsonObject["completion_tokens_details"]?.jsonObjectOrNull?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull,
            // OpenRouter reports the generation cost (USD) here when the request asks for it
            // via usage:{include:true}. Other OpenAI-compatible providers omit it -> null.
            cost = jsonObject["cost"]?.jsonPrimitive?.doubleOrNull
        )
    }

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        // D3: a part the model can actually receive must stop the message collapsing to its
        // bare text. Before this, Audio/Video counted as "not going to be sent", so a message
        // of [Audio, Text] collapsed to just the text and the attachment vanished.
        val gonnaSend =
            filter {
                it is UIMessagePart.Text || it is UIMessagePart.Image ||
                    it is UIMessagePart.Audio || it is UIMessagePart.Video
            }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }
}
