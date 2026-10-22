package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure decision logic and the request/response shapes of the SiliconFlow video path.
 */
class SiliconFlowVideoRequestTest {

    private val model = Model(modelId = "Wan-AI/Wan2.2-T2V-A14B")

    // ---- host detection ------------------------------------------------------------------

    @Test
    fun `both SiliconFlow hosts are recognised`() {
        assertTrue(isSiliconFlowBaseUrl("https://api.siliconflow.cn/v1"))
        assertTrue(isSiliconFlowBaseUrl("https://api.siliconflow.com/v1"))
    }

    @Test
    fun `a lookalike host is not SiliconFlow`() {
        assertFalse(isSiliconFlowBaseUrl("https://siliconflow.cn/v1"))
        assertFalse(isSiliconFlowBaseUrl("https://api.siliconflow.cn.evil.com/v1"))
        assertFalse(isSiliconFlowBaseUrl("https://api.openai.com/v1"))
    }

    @Test
    fun `the native root drops the v1 path`() {
        assertEquals("https://api.siliconflow.cn", siliconFlowNativeRoot("https://api.siliconflow.cn/v1"))
        assertEquals("https://api.siliconflow.com", siliconFlowNativeRoot("https://api.siliconflow.com/v1/"))
        assertNull(siliconFlowNativeRoot("https://api.openai.com/v1"))
    }

    // ---- knobs ---------------------------------------------------------------------------

    @Test
    fun `the three shapes map onto the only three accepted image sizes`() {
        assertEquals("1280x720", siliconFlowImageSize(ImageAspectRatio.LANDSCAPE))
        assertEquals("720x1280", siliconFlowImageSize(ImageAspectRatio.PORTRAIT))
        assertEquals("960x960", siliconFlowImageSize(ImageAspectRatio.SQUARE))
    }

    // ---- body ----------------------------------------------------------------------------

    @Test
    fun `text-to-video sends the size and no image`() {
        val body = buildSiliconFlowVideoRequestBody(
            model = model,
            prompt = "a cat",
            aspectRatio = ImageAspectRatio.SQUARE,
            firstFrameImage = null,
        )
        assertEquals("Wan-AI/Wan2.2-T2V-A14B", body["model"]!!.jsonPrimitive.content)
        assertEquals("a cat", body["prompt"]!!.jsonPrimitive.content)
        assertEquals("960x960", body["image_size"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("image"))
    }

    @Test
    fun `image-to-video adds the bare base64 frame`() {
        val body = buildSiliconFlowVideoRequestBody(
            model = model,
            prompt = "a cat",
            aspectRatio = ImageAspectRatio.PORTRAIT,
            firstFrameImage = "AAAA",
        )
        assertEquals("AAAA", body["image"]!!.jsonPrimitive.content)
        assertEquals("720x1280", body["image_size"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the status call is a POST body carrying the requestId`() {
        val body = buildSiliconFlowStatusRequestBody("req-1")
        assertEquals("req-1", body["requestId"]!!.jsonPrimitive.content)
        assertEquals("/v1/video/submit", SILICONFLOW_VIDEO_SUBMIT_PATH)
        assertEquals("/v1/video/status", SILICONFLOW_VIDEO_STATUS_PATH)
    }

    // ---- parsing -------------------------------------------------------------------------

    @Test
    fun `the submit response yields the requestId`() {
        assertEquals("req-1", parseSiliconFlowRequestId("""{"requestId":"req-1"}"""))
        assertEquals("req-2", parseSiliconFlowRequestId("""{"request_id":"req-2"}"""))
    }

    @Test
    fun `a finished job exposes the first video url`() {
        val body = """{"status":"Succeed","reason":"","results":{"videos":[{"url":"https://cdn/v.mp4"}],"seed":7},"requestId":"req-1"}"""
        assertEquals("Succeed", parseSiliconFlowStatus(body))
        assertEquals("https://cdn/v.mp4", parseSiliconFlowVideoUrl(body))
    }

    @Test
    fun `the url fallbacks cover a flattened gateway shape`() {
        assertEquals("https://cdn/a.mp4", parseSiliconFlowVideoUrl("""{"videos":[{"url":"https://cdn/a.mp4"}]}"""))
        assertEquals("https://cdn/b.mp4", parseSiliconFlowVideoUrl("""{"url":"https://cdn/b.mp4"}"""))
    }

    @Test
    fun `an in-flight job has a status but no url`() {
        val body = """{"status":"InProgress","requestId":"req-1"}"""
        assertEquals("InProgress", parseSiliconFlowStatus(body))
        assertNull(parseSiliconFlowVideoUrl(body))
    }

    @Test
    fun `a failure surfaces reason first, then message`() {
        assertEquals("nsfw", parseSiliconFlowErrorMessage("""{"status":"Failed","reason":"nsfw"}"""))
        assertEquals("boom", parseSiliconFlowErrorMessage("""{"message":"boom"}"""))
    }

    @Test
    fun `a garbled body parses to null instead of throwing`() {
        assertNull(parseSiliconFlowRequestId("<html>502</html>"))
        assertNull(parseSiliconFlowStatus(""))
        assertNull(parseSiliconFlowVideoUrl("not json"))
    }
}
