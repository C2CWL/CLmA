package com.clma.ai.provider.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.clma.ai.core.MessageRole
import com.clma.ai.core.Tool
import com.clma.ai.provider.BuiltInTools
import com.clma.ai.provider.Model
import com.clma.ai.provider.ModelAbility
import com.clma.ai.provider.TextGenerationParams
import com.clma.ai.provider.stream.SseEvent
import com.clma.ai.ui.GoogleThoughtMetadata
import com.clma.ai.ui.ServerToolMetadata
import com.clma.ai.ui.ServerToolProtocol
import com.clma.ai.ui.ServerToolStatus
import com.clma.ai.ui.StreamChunkHandler
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.ai.ui.metadataAs
import com.clma.ai.ui.toMetadata
import com.clma.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleToolCombinationTest {
    private val provider = GoogleProvider(OkHttpClient())

    @Test
    fun `built-in and function tools enable server-side tool invocations`() {
        val body = invokeBuildRequestBody(
            messages = listOf(UIMessage.user("search and call a tool")),
            params = TextGenerationParams(
                model = toolModel(tools = setOf(BuiltInTools.Search)),
                tools = listOf(testTool()),
            ),
        )

        val tools = body["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertTrue(tools.any { it.jsonObject.containsKey("functionDeclarations") })
        assertTrue(tools.any { it.jsonObject.containsKey("googleSearch") })
        assertTrue(
            body["toolConfig"]!!.jsonObject["includeServerSideToolInvocations"]!!
                .jsonPrimitive.boolean
        )
    }

    @Test
    fun `single tool category does not enable server-side tool invocations`() {
        val builtInOnly = invokeBuildRequestBody(
            messages = listOf(UIMessage.user("search")),
            params = TextGenerationParams(
                model = toolModel(tools = setOf(BuiltInTools.Search)),
            ),
        )
        val functionOnly = invokeBuildRequestBody(
            messages = listOf(UIMessage.user("call a tool")),
            params = TextGenerationParams(
                model = toolModel(),
                tools = listOf(testTool()),
            ),
        )

        assertFalse(builtInOnly.containsKey("toolConfig"))
        assertFalse(functionOnly.containsKey("toolConfig"))
    }

    @Test
    fun `server tool context and function id survive streaming and history replay`() {
        val toolCallPart = buildJsonObject {
            put("thoughtSignature", "search-call-signature")
            put("toolCall", buildJsonObject {
                put("toolType", "GOOGLE_SEARCH_WEB")
                put("args", buildJsonObject {
                    putJsonArray("queries") { add("latest weather") }
                })
                put("id", "server-call-1")
            })
        }
        val toolResponsePart = buildJsonObject {
            put("thoughtSignature", "search-response-signature")
            put("toolResponse", buildJsonObject {
                put("toolType", "GOOGLE_SEARCH_WEB")
                put("response", buildJsonObject {
                    put("search_suggestions", "weather result")
                })
                put("id", "server-call-1")
            })
        }
        val functionCallPart = buildJsonObject {
            put("functionCall", buildJsonObject {
                put("name", "save_weather")
                put("args", buildJsonObject { put("value", "sunny") })
                put("id", "function-call-1")
            })
            put("thoughtSignature", "function-signature")
        }
        val event = SseEvent(data = json.encodeToString(buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    put("content", buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            add(toolCallPart)
                            add(toolResponsePart)
                            add(functionCallPart)
                        })
                    })
                })
            }
        }))

        val decoder = GoogleStreamDecoder("response-1", "gemini-3.7-flash")
        val handler = StreamChunkHandler(toolModel(tools = setOf(BuiltInTools.Search)))
        var messages = listOf(UIMessage.user("search and save"))
        decoder.accept(event).chunks.forEach { messages = handler.handle(messages, it) }
        decoder.onClosed().forEach { messages = handler.handle(messages, it) }

        val assistant = messages.last()
        val serverTool = assistant.parts.filterIsInstance<UIMessagePart.ServerTool>().single()
        assertEquals(ServerToolStatus.COMPLETED, serverTool.status)
        assertEquals("server-call-1", serverTool.toolCallId)
        assertEquals(
            ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
            serverTool.metadataAs<ServerToolMetadata>()?.protocol
        )
        assertEquals(toolCallPart, serverTool.metadataAs<ServerToolMetadata>()?.call)
        assertEquals(toolResponsePart, serverTool.metadataAs<ServerToolMetadata>()?.result)

        val functionTool = assistant.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("function-call-1", functionTool.toolCallId)

        val executedAssistant = assistant.copy(parts = assistant.parts.map { part ->
            if (part is UIMessagePart.Tool) {
                part.copy(output = listOf(UIMessagePart.Text("saved")))
            } else {
                part
            }
        })
        val contents = invokeBuildContents(listOf(messages.first(), executedAssistant))
        val modelParts = contents[1].jsonObject["parts"]!!.jsonArray
        assertEquals(toolCallPart, modelParts[0])
        assertEquals(toolResponsePart, modelParts[1])
        assertEquals(
            "function-call-1",
            modelParts[2].jsonObject["functionCall"]!!.jsonObject["id"]!!.jsonPrimitive.content
        )
        assertEquals(
            "function-call-1",
            contents[2].jsonObject["parts"]!!.jsonArray.single()
                .jsonObject["functionResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `unsigned function call gets the skip signature on gemini 3 only`() {
        val messages = listOf(
            UIMessage.user("save the weather"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "save_weather",
                        input = """{"city":"Shanghai"}""",
                        output = listOf(UIMessagePart.Text("saved")),
                    )
                ),
            ),
        )

        // Gemini 3 rejects a replayed functionCall without thoughtSignature - the documented
        // escape hatch for calls it did not produce itself is this sentinel string.
        val gemini3 = provider.buildCompletionRequestBody(
            messages,
            TextGenerationParams(model = toolModel(), tools = listOf(testTool())),
        )
        val call3 = firstFunctionCall(gemini3)
        assertEquals(
            "skip_thought_signature_validator",
            call3["thoughtSignature"]!!.jsonPrimitive.content,
        )

        // Older models don't know the field, so it must not appear there.
        val gemini25 = provider.buildCompletionRequestBody(
            messages,
            TextGenerationParams(model = toolModel(modelId = "gemini-2.5-flash"), tools = listOf(testTool())),
        )
        assertFalse(firstFunctionCall(gemini25).containsKey("thoughtSignature"))
    }

    @Test
    fun `unsigned function call gets the skip signature on code assist claude models`() {
        val messages = listOf(
            UIMessage.user("save the weather"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "save_weather",
                        input = """{"city":"Shanghai"}""",
                        output = listOf(UIMessagePart.Text("saved")),
                    )
                ),
            ),
        )

        // Cloud Code Assist fronts Anthropic models and validates their replayed functionCall with
        // the same rule as Gemini 3, so the reported 400 ("Function call is missing a
        // thought_signature in functionCall parts", position 27) needs the same sentinel.
        val claude = provider.buildCompletionRequestBody(
            messages,
            TextGenerationParams(
                model = toolModel(modelId = "gemini-claude-opus-4-6-thinking"),
                tools = listOf(testTool()),
            ),
        )
        assertEquals(
            "skip_thought_signature_validator",
            firstFunctionCall(claude)["thoughtSignature"]!!.jsonPrimitive.content,
        )

        // A Code Assist Claude id the registry does not know yet must not fall back to "unsigned".
        val unknownClaude = provider.buildCompletionRequestBody(
            messages,
            TextGenerationParams(
                model = toolModel(modelId = "gemini-claude-haiku-9-thinking"),
                tools = listOf(testTool()),
            ),
        )
        assertEquals(
            "skip_thought_signature_validator",
            firstFunctionCall(unknownClaude)["thoughtSignature"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `every model gets the skip signature except gemini 2_5 and older`() {
        val messages = listOf(
            UIMessage.user("save the weather"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "save_weather",
                        input = """{"city":"Shanghai"}""",
                        output = listOf(UIMessagePart.Text("saved")),
                    )
                ),
            ),
        )

        // Cloud Code Assist validates every replayed functionCall, so anything it fronts - current
        // or not yet in the registry - needs the sentinel. Only Gemini 2.5 and earlier reject it.
        listOf("gemini-3.8-pro", "gemini-4-pro", "claude-5-sonnet", "gpt-oss-120b").forEach { id ->
            val body = provider.buildCompletionRequestBody(
                messages,
                TextGenerationParams(model = toolModel(modelId = id), tools = listOf(testTool())),
            )
            assertEquals(
                "skip_thought_signature_validator",
                firstFunctionCall(body)["thoughtSignature"]!!.jsonPrimitive.content,
                id,
            )
        }

        listOf("gemini-2.5-pro", "gemini-2.0-flash", "gemini-1.5-pro").forEach { id ->
            val body = provider.buildCompletionRequestBody(
                messages,
                TextGenerationParams(model = toolModel(modelId = id), tools = listOf(testTool())),
            )
            assertFalse(firstFunctionCall(body).containsKey("thoughtSignature"), id)
        }
    }

        @Test
    fun `a real thought signature wins over the skip sentinel`() {
        val messages = listOf(
            UIMessage.user("save the weather"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "save_weather",
                        input = """{"city":"Shanghai"}""",
                        output = listOf(UIMessagePart.Text("saved")),
                        metadata = GoogleThoughtMetadata(thoughtSignature = "sig-real").toMetadata(),
                    )
                ),
            ),
        )

        val call = firstFunctionCall(
            provider.buildCompletionRequestBody(
                messages,
                TextGenerationParams(model = toolModel(), tools = listOf(testTool())),
            )
        )
        assertEquals("sig-real", call["thoughtSignature"]!!.jsonPrimitive.content)
    }

    private fun firstFunctionCall(body: JsonObject): JsonObject =
        body["contents"]!!.jsonArray
            .flatMap { it.jsonObject["parts"]!!.jsonArray }
            .first { it.jsonObject.containsKey("functionCall") }
            .jsonObject

    private fun invokeBuildRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): JsonObject {
        return provider.buildCompletionRequestBody(messages, params)
    }

    private fun invokeBuildContents(
        messages: List<UIMessage>,
        requireThoughtSignatures: Boolean = false,
    ): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildContents",
            List::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(provider, messages, requireThoughtSignatures) as JsonArray
    }

    private fun toolModel(
        tools: Set<BuiltInTools> = emptySet(),
        modelId: String = "gemini-3.7-flash",
    ) = Model(
        modelId = modelId,
        abilities = listOf(ModelAbility.TOOL),
        tools = tools,
    )

    private fun testTool() = Tool(
        name = "save_weather",
        description = "Save weather information",
        execute = { emptyList() },
    )
}
