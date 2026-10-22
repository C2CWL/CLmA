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
 * Pins the pure decision logic and the request/response shapes of the Zhipu (CogVideoX) video path.
 */
class ZhipuVideoRequestTest {

    private val model = Model(modelId = "cogvideox-3")

    // ---- host detection ------------------------------------------------------------------

    @Test
    fun `mainland and international Zhipu hosts are both recognised`() {
        assertTrue(isZhipuBaseUrl("https://open.bigmodel.cn/api/paas/v4"))
        assertTrue(isZhipuBaseUrl("https://api.z.ai/api/paas/v4"))
    }

    @Test
    fun `a foreign or squatted host is not Zhipu`() {
        assertFalse(isZhipuBaseUrl("https://notbigmodel.cn/api/paas/v4"))
        assertFalse(isZhipuBaseUrl("https://open.bigmodel.cn.evil.com/api"))
        assertFalse(isZhipuBaseUrl("https://api.openai.com/v1"))
    }

    @Test
    fun `the native root drops the paas path`() {
        assertEquals("https://open.bigmodel.cn", zhipuNativeRoot("https://open.bigmodel.cn/api/paas/v4"))
        assertEquals("https://api.z.ai", zhipuNativeRoot("https://api.z.ai/api/paas/v4"))
        assertNull(zhipuNativeRoot("https://api.openai.com/v1"))
    }

    @Test
    fun `the async result path carries the task id`() {
        assertEquals("/api/paas/v4/async-result/abc", zhipuAsyncResultPath("abc"))
        assertEquals("/api/paas/v4/videos/generations", ZHIPU_VIDEO_GENERATION_PATH)
    }

    // ---- knobs ---------------------------------------------------------------------------

    @Test
    fun `the three shapes map onto CogVideoX size pairs`() {
        assertEquals("1920x1080", zhipuVideoSize(ImageAspectRatio.LANDSCAPE))
        assertEquals("1080x1920", zhipuVideoSize(ImageAspectRatio.PORTRAIT))
        assertEquals("1024x1024", zhipuVideoSize(ImageAspectRatio.SQUARE))
    }

    @Test
    fun `duration is snapped to the only two values the model takes`() {
        assertEquals(5, zhipuVideoDuration(2))
        assertEquals(5, zhipuVideoDuration(7))
        assertEquals(10, zhipuVideoDuration(8))
        assertEquals(10, zhipuVideoDuration(30))
        assertNull(zhipuVideoDuration(null))
    }

    // ---- body ----------------------------------------------------------------------------

    @Test
    fun `text-to-video sends a size and no image`() {
        val body = buildZhipuVideoRequestBody(
            model = model,
            prompt = "a cat",
            aspectRatio = ImageAspectRatio.LANDSCAPE,
            durationSeconds = 5,
            firstFrameImage = null,
        )
        assertEquals("cogvideox-3", body["model"]!!.jsonPrimitive.content)
        assertEquals("a cat", body["prompt"]!!.jsonPrimitive.content)
        assertEquals("1920x1080", body["size"]!!.jsonPrimitive.content)
        assertEquals(5, body["duration"]!!.jsonPrimitive.content.toInt())
        assertFalse(body.containsKey("image_url"))
    }

    @Test
    fun `image-to-video sends the frame and drops the size so the source aspect wins`() {
        val body = buildZhipuVideoRequestBody(
            model = model,
            prompt = "a cat",
            aspectRatio = ImageAspectRatio.LANDSCAPE,
            durationSeconds = null,
            firstFrameImage = "AAAA",
        )
        assertEquals("AAAA", body["image_url"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("size"))
        assertFalse(body.containsKey("duration"))
    }

    // ---- parsing -------------------------------------------------------------------------

    @Test
    fun `the create response is polled by id, not request_id`() {
        val body = """{"model":"cogvideox-3","id":"task-1","request_id":"req-1","task_status":"PROCESSING"}"""
        assertEquals("task-1", parseZhipuTaskId(body))
        assertEquals("PROCESSING", parseZhipuTaskStatus(body))
    }

    @Test
    fun `a gateway that only answers request_id still yields a handle`() {
        assertEquals("req-9", parseZhipuTaskId("""{"request_id":"req-9"}"""))
    }

    @Test
    fun `a finished task exposes the first video result`() {
        val body = """{"id":"t","task_status":"SUCCESS","video_result":[{"url":"https://cdn/v.mp4","cover_image_url":"https://cdn/c.png"}]}"""
        assertEquals("SUCCESS", parseZhipuTaskStatus(body))
        assertEquals("https://cdn/v.mp4", parseZhipuVideoUrl(body))
    }

    @Test
    fun `an error envelope surfaces its message`() {
        assertEquals("invalid api key", parseZhipuErrorMessage("""{"error":{"code":"1002","message":"invalid api key"}}"""))
    }

    @Test
    fun `a response without results parses to null`() {
        assertNull(parseZhipuVideoUrl("""{"id":"t","task_status":"PROCESSING"}"""))
        assertNull(parseZhipuTaskId("<html>"))
        assertNull(parseZhipuTaskStatus(""))
    }

    @Test
    fun `the unwrapped video_result array is not confused with a nested one`() {
        // Guard against an over-eager `jsonArray` reach: the field must be a list of objects.
        val body = """{"video_result":[{"url":"u1"},{"url":"u2"}]}"""
        assertEquals("u1", parseZhipuVideoUrl(body))
        assertEquals(2, jsonArrayOf(body).size)
    }

    private fun jsonArrayOf(body: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["video_result"]!!.jsonArray
}
