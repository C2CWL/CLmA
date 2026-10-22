package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure decisions behind the DashScope image path. The live contract these mirror was
 * confirmed against the real endpoint: `POST .../text2image/image-synthesis` with
 * `X-DashScope-Async: enable` returns `{output:{task_id, task_status:"PENDING"}}`, and
 * `GET /api/v1/tasks/{id}` eventually returns `{output:{task_status:"SUCCEEDED",
 * results:[{url:...}]}}`.
 *
 * Lives under `app/src/test` (not `ai/src/test`) on purpose: CI runs only
 * `:app:testDebugUnitTest`, so this is the only place the gate actually executes it — the same
 * reason `OpenAIMultimodalPartsTest` sits here.
 */
class DashScopeImageRequestTest {

    // ---- host detection ----

    @Test
    fun dashscope_compatible_mode_url_is_recognised() {
        assertTrue(isDashScopeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
        assertTrue(isDashScopeBaseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1"))
    }

    @Test
    fun other_providers_are_not_dashscope() {
        assertFalse(isDashScopeBaseUrl("https://api.openai.com/v1"))
        assertFalse(isDashScopeBaseUrl("https://openrouter.ai/api/v1"))
        // Lookalike hosts must not match: a different domain that merely ends in the suffix,
        // and a typo-squat whose first label is not "dashscope".
        assertFalse(isDashScopeBaseUrl("https://notdashscope.aliyuncs.com.evil.test/v1"))
        assertFalse(isDashScopeBaseUrl("https://notdashscope.aliyuncs.com/v1"))
        assertFalse(isDashScopeBaseUrl("not a url"))
    }

    // ---- native root derivation ----

    @Test
    fun native_root_drops_the_compatible_mode_path() {
        assertEquals(
            "https://dashscope.aliyuncs.com",
            dashScopeNativeRoot("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
    }

    @Test
    fun native_root_keeps_a_non_default_port_and_scheme() {
        assertEquals(
            "http://dashscope.aliyuncs.com:8443",
            dashScopeNativeRoot("http://dashscope.aliyuncs.com:8443/compatible-mode/v1"),
        )
    }

    @Test
    fun native_root_is_null_for_non_dashscope_hosts() {
        assertNull(dashScopeNativeRoot("https://api.openai.com/v1"))
        assertNull(dashScopeNativeRoot("garbage"))
    }

    // ---- sizes and request body ----

    @Test
    fun size_uses_dashscope_star_notation() {
        assertEquals("1328*1328", dashScopeImageSize(ImageAspectRatio.SQUARE))
        assertEquals("1664*928", dashScopeImageSize(ImageAspectRatio.LANDSCAPE))
        assertEquals("928*1664", dashScopeImageSize(ImageAspectRatio.PORTRAIT))
    }

    @Test
    fun submit_body_has_model_input_and_parameters() {
        val model = Model(modelId = "wanx2.1-t2i-turbo")
        val body = buildDashScopeImageRequestBody(
            model = model,
            prompt = "a red panda, watercolour",
            aspectRatio = ImageAspectRatio.LANDSCAPE,
            numOfImages = 2,
        )
        assertEquals("wanx2.1-t2i-turbo", body["model"]!!.jsonPrimitive.content)
        assertEquals("a red panda, watercolour", body["input"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        val params = body["parameters"]!!.jsonObject
        assertEquals("1664*928", params["size"]!!.jsonPrimitive.content)
        assertEquals("2", params["n"]!!.jsonPrimitive.content)
    }

    // ---- new-generation (Qwen-Image 2.x / 3.x) routing ----

    @Test
    fun new_generation_qwen_image_models_use_the_multimodal_endpoint() {
        assertTrue(dashScopeUsesMultimodalEndpoint("qwen-image-3.0"))
        assertTrue(dashScopeUsesMultimodalEndpoint("qwen-image-3.0-pro"))
        assertTrue(dashScopeUsesMultimodalEndpoint("qwen-image-2.1-pro"))
        // Case/space tolerant.
        assertTrue(dashScopeUsesMultimodalEndpoint(" Qwen-Image-3.0 "))
    }

    @Test
    fun legacy_image_models_keep_the_async_task_endpoint() {
        assertFalse(dashScopeUsesMultimodalEndpoint("qwen-image"))
        assertFalse(dashScopeUsesMultimodalEndpoint("qwen-image-plus"))
        assertFalse(dashScopeUsesMultimodalEndpoint("qwen-image-max"))
        assertFalse(dashScopeUsesMultimodalEndpoint("wanx2.1-t2i-turbo"))
        assertFalse(dashScopeUsesMultimodalEndpoint("wan2.2-t2i-flash"))
        // A chat model is never an image model.
        assertFalse(dashScopeUsesMultimodalEndpoint("qwen-turbo"))
    }

    @Test
    fun multimodal_body_wraps_the_prompt_in_a_user_message() {
        val body = buildDashScopeMultimodalImageRequestBody(
            model = Model(modelId = "qwen-image-3.0"),
            prompt = "a single red circle",
            aspectRatio = ImageAspectRatio.SQUARE,
            numOfImages = 1,
        )
        assertEquals("qwen-image-3.0", body["model"]!!.jsonPrimitive.content)
        val message = body["input"]!!.jsonObject["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        val content = message["content"]!!.jsonArray.single().jsonObject
        assertEquals("a single red circle", content["text"]!!.jsonPrimitive.content)
        val params = body["parameters"]!!.jsonObject
        assertEquals("1328*1328", params["size"]!!.jsonPrimitive.content)
        assertEquals("1", params["n"]!!.jsonPrimitive.content)
    }

    @Test
    fun parses_image_urls_from_a_synchronous_multimodal_response() {
        val body = """
            {"request_id":"r1","output":{"choices":[
                {"finish_reason":"stop","message":{"role":"assistant","content":[
                    {"image":"https://dashscope-result.oss-cn.aliyuncs.com/1.png"}
                ]}}
            ]}}
        """.trimIndent()
        assertEquals(
            listOf("https://dashscope-result.oss-cn.aliyuncs.com/1.png"),
            parseDashScopeMultimodalImageUrls(body),
        )
    }

    @Test
    fun multimodal_parser_never_throws_on_malformed_bodies() {
        for (body in listOf("", "not json", "{}", """{"output":null}""", """{"output":{"choices":[]}}""")) {
            assertEquals(emptyList<String>(), parseDashScopeMultimodalImageUrls(body))
        }
    }

    @Test
    fun detects_the_endpoint_model_mismatch_rejection() {
        // The exact wording Alibaba returns when the model is not valid for the endpoint.
        assertTrue(
            isDashScopeEndpointModelMismatch(
                """{"code":"InvalidParameter","message":"url error, please check url！"}"""
            )
        )
        // Any other failure is not retryable this way.
        assertFalse(
            isDashScopeEndpointModelMismatch(
                """{"code":"InvalidApiKey","message":"Invalid API-key provided."}"""
            )
        )
        assertFalse(isDashScopeEndpointModelMismatch(""))
    }

    // ---- response parsing ----

    @Test
    fun parses_task_id_and_status_from_submit_response() {
        val body = """{"request_id":"r1","output":{"task_id":"abc-123","task_status":"PENDING"}}"""
        assertEquals("abc-123", parseDashScopeTaskId(body))
        assertEquals("PENDING", parseDashScopeTaskStatus(body))
    }

    @Test
    fun parses_every_result_url_from_a_succeeded_task() {
        val body = """
            {"output":{"task_id":"abc","task_status":"SUCCEEDED","results":[
                {"url":"https://dashscope-result.oss-cn.aliyuncs.com/1.png"},
                {"url":"https://dashscope-result.oss-cn.aliyuncs.com/2.png"}
            ]}}
        """.trimIndent()
        assertEquals(
            listOf(
                "https://dashscope-result.oss-cn.aliyuncs.com/1.png",
                "https://dashscope-result.oss-cn.aliyuncs.com/2.png",
            ),
            parseDashScopeImageUrls(body),
        )
    }

    @Test
    fun parses_failure_message() {
        val body = """{"output":{"task_status":"FAILED","message":"DataInspectionFailed"}}"""
        assertEquals("FAILED", parseDashScopeTaskStatus(body))
        assertEquals("DataInspectionFailed", parseDashScopeTaskMessage(body))
    }

    @Test
    fun malformed_bodies_never_throw() {
        for (body in listOf("", "not json", "{}", """{"output":null}""", """{"output":{"results":[]}}""")) {
            assertNull(parseDashScopeTaskId(body))
            assertNull(parseDashScopeTaskStatus(body))
            assertEquals(emptyList<String>(), parseDashScopeImageUrls(body))
        }
    }
}
