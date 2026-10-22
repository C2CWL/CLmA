package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
 * Pins the pure decisions behind the DashScope Wan video path. Shapes mirror the live contract:
 * `POST .../video-generation/video-synthesis` with `X-DashScope-Async: enable` returns
 * `{output:{task_id, task_status:"PENDING"}}`, and the shared `GET /api/v1/tasks/{id}` eventually
 * returns `{output:{task_status:"SUCCEEDED", video_url:...}}`.
 *
 * Lives under `app/src/test` for the same reason [DashScopeImageRequestTest] does: CI runs only the
 * app module's unit tests.
 */
class DashScopeVideoRequestTest {

    private fun body(modelId: String, aspect: ImageAspectRatio, duration: Int? = null) =
        buildDashScopeVideoRequestBody(
            model = Model(modelId = modelId),
            prompt = "a cat",
            aspectRatio = aspect,
            durationSeconds = duration,
        )

    // ---- size mapping ----

    @Test
    fun size_maps_each_shape_to_a_supported_720p_resolution() {
        assertEquals("1280*720", dashScopeVideoSize(ImageAspectRatio.LANDSCAPE))
        assertEquals("720*1280", dashScopeVideoSize(ImageAspectRatio.PORTRAIT))
        assertEquals("960*960", dashScopeVideoSize(ImageAspectRatio.SQUARE))
    }

    // ---- duration ----

    @Test
    fun duration_is_omitted_for_the_fixed_five_second_models() {
        assertNull(dashScopeVideoDuration("wan2.1-t2v-turbo", 10))
        assertNull(dashScopeVideoDuration("wan2.2-t2v-plus", 5))
        assertNull(dashScopeVideoDuration("wanx2.1-t2v-plus", 5))
    }

    @Test
    fun duration_is_snapped_to_five_or_ten_for_wan25() {
        assertEquals(5, dashScopeVideoDuration("wan2.5-t2v-preview", 2))
        assertEquals(5, dashScopeVideoDuration("wan2.5-t2v-preview", 7))
        assertEquals(10, dashScopeVideoDuration("wan2.5-t2v-preview", 8))
        assertEquals(10, dashScopeVideoDuration("wan2.5-t2v-preview", 15))
    }

    @Test
    fun duration_is_clamped_into_two_to_fifteen_otherwise() {
        assertEquals(2, dashScopeVideoDuration("wan2.6-t2v", 0))
        assertEquals(9, dashScopeVideoDuration("wan2.6-t2v", 9))
        assertEquals(15, dashScopeVideoDuration("wan2.6-t2v", 99))
    }

    @Test
    fun duration_is_omitted_when_not_requested() {
        assertNull(dashScopeVideoDuration("wan2.6-t2v", null))
    }

    // ---- request body ----

    @Test
    fun body_carries_model_prompt_size_and_no_duration_when_unset() {
        val json = body("wan2.2-t2v-plus", ImageAspectRatio.LANDSCAPE).jsonObject
        assertEquals("wan2.2-t2v-plus", json["model"]!!.jsonPrimitive.content)
        assertEquals("a cat", json["input"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        val params = json["parameters"]!!.jsonObject
        assertEquals("1280*720", params["size"]!!.jsonPrimitive.content)
        assertFalse(params.containsKey("duration"))
    }

    @Test
    fun body_includes_a_duration_when_the_model_accepts_one() {
        val params = body("wan2.6-t2v", ImageAspectRatio.PORTRAIT, duration = 9).jsonObject["parameters"]!!
            .jsonObject
        assertEquals("720*1280", params["size"]!!.jsonPrimitive.content)
        assertEquals(9, params["duration"]!!.jsonPrimitive.int)
    }

    // ---- response parsing ----

    @Test
    fun video_url_is_read_out_of_a_succeeded_task() {
        val body = """{"output":{"task_id":"t1","task_status":"SUCCEEDED","video_url":"https://x/y.mp4"}}"""
        assertEquals("https://x/y.mp4", parseDashScopeVideoUrl(body))
    }

    @Test
    fun video_url_is_null_when_absent_or_unparseable() {
        assertNull(parseDashScopeVideoUrl("""{"output":{"task_id":"t1","task_status":"RUNNING"}}"""))
        assertNull(parseDashScopeVideoUrl("not json"))
    }

    @Test
    fun task_id_and_status_are_read_from_the_shared_envelope() {
        val body = """{"output":{"task_id":"abc","task_status":"PENDING"}}"""
        assertEquals("abc", parseDashScopeTaskId(body))
        assertEquals("PENDING", parseDashScopeTaskStatus(body))
        assertTrue(isDashScopeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
    }

    @Test
    fun synthesis_path_is_the_native_video_endpoint() {
        assertEquals("/api/v1/services/aigc/video-generation/video-synthesis", DASHSCOPE_VIDEO_SYNTHESIS_PATH)
    }

    // ---- image-to-video (first frame) ----

    @Test
    fun image_to_video_body_carries_img_url_and_omits_size() {
        val json = buildDashScopeImageToVideoRequestBody(
            model = Model(modelId = "wan2.2-i2v-plus"),
            prompt = "a cat walks",
            firstFrameUrl = "data:image/png;base64,AAAA",
            durationSeconds = 10,
        ).jsonObject

        assertEquals("wan2.2-i2v-plus", json["model"]!!.jsonPrimitive.content)
        assertEquals("a cat walks", json["input"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,AAAA", json["input"]!!.jsonObject["img_url"]!!.jsonPrimitive.content)
        // wan2.2 i2v is fixed at 5 s, so there is nothing to send - not even an empty parameters.
        assertFalse(json.containsKey("parameters"))
    }

    @Test
    fun image_to_video_keeps_a_duration_the_model_accepts() {
        val params = buildDashScopeImageToVideoRequestBody(
            model = Model(modelId = "wan2.6-i2v"),
            prompt = "p",
            firstFrameUrl = "https://x/y.png",
            durationSeconds = 9,
        ).jsonObject["parameters"]!!.jsonObject

        assertEquals(9, params["duration"]!!.jsonPrimitive.int)
    }

    @Test
    fun image2video_path_is_the_native_first_frame_endpoint() {
        assertEquals(
            "/api/v1/services/aigc/image2video/video-synthesis",
            DASHSCOPE_IMAGE2VIDEO_SYNTHESIS_PATH,
        )
    }
}
