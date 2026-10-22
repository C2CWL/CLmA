package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.int
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
 * Pins the pure decision logic and the request/response shapes of the MiniMax video path, mirroring
 * [DashScopeVideoRequest]: everything decidable without a socket lives in the helper file.
 */
class MiniMaxVideoRequestTest {

    private val classic = Model(modelId = "MiniMax-Hailuo-2.3")
    private val h3 = Model(modelId = "MiniMax-H3")

    // ---- host detection ------------------------------------------------------------------

    @Test
    fun `all four documented MiniMax hosts are recognised`() {
        assertTrue(isMiniMaxBaseUrl("https://api.minimaxi.com/v1"))
        assertTrue(isMiniMaxBaseUrl("https://api.minimax.chat/v1"))
        assertTrue(isMiniMaxBaseUrl("https://api.minimax.cn/v1"))
        assertTrue(isMiniMaxBaseUrl("https://api.minimax.io/v1"))
    }

    @Test
    fun `a lookalike or foreign host is not MiniMax`() {
        assertFalse(isMiniMaxBaseUrl("https://notminimaxi.com/v1"))
        assertFalse(isMiniMaxBaseUrl("https://foo.minimax.chat/v1"))
        assertFalse(isMiniMaxBaseUrl("https://api.openai.com/v1"))
        // No scheme: not a usable base URL either.
        assertFalse(isMiniMaxBaseUrl("api.minimaxi.com/v1"))
    }

    @Test
    fun `the native root drops the OpenAI-compatible path`() {
        assertEquals("https://api.minimaxi.com", minimaxNativeRoot("https://api.minimaxi.com/v1"))
        assertEquals("https://api.minimax.chat", minimaxNativeRoot("https://api.minimax.chat/v1/"))
        assertNull(minimaxNativeRoot("https://api.openai.com/v1"))
    }

    @Test
    fun `the classic task urls keep the vendor query parameter form`() {
        assertEquals(
            "https://api.minimax.cn/v1/query/video_generation?task_id=abc",
            minimaxVideoQueryUrl("https://api.minimax.cn", "abc"),
        )
        assertEquals(
            "https://api.minimax.cn/v1/files/retrieve?file_id=f1",
            minimaxFileRetrieveUrl("https://api.minimax.cn", "f1"),
        )
    }

    @Test
    fun `the H3 task url puts the id in the path`() {
        assertEquals("/v2/query/video_generation/abc", minimaxH3QueryPath("abc"))
    }

    // ---- dialect -------------------------------------------------------------------------

    @Test
    fun `only the H3 generation speaks the v2 content dialect`() {
        assertTrue(minimaxUsesH3Dialect("MiniMax-H3"))
        assertTrue(minimaxUsesH3Dialect("  minimax-h3-max "))
        assertFalse(minimaxUsesH3Dialect("MiniMax-Hailuo-2.3"))
        assertFalse(minimaxUsesH3Dialect("MiniMax-Hailuo-02"))
        assertFalse(minimaxUsesH3Dialect("T2V-01"))
    }

    // ---- knobs ---------------------------------------------------------------------------

    @Test
    fun `H3 duration is clamped to 4 to 15 and the classic one snapped to 6 or 10`() {
        assertEquals(4, minimaxVideoDuration("MiniMax-H3", 2))
        assertEquals(15, minimaxVideoDuration("MiniMax-H3", 30))
        assertEquals(5, minimaxVideoDuration("MiniMax-H3-Max", 5))

        assertEquals(6, minimaxVideoDuration("MiniMax-Hailuo-2.3", 3))
        assertEquals(6, minimaxVideoDuration("MiniMax-Hailuo-2.3", 7))
        assertEquals(10, minimaxVideoDuration("MiniMax-Hailuo-2.3", 8))
        assertEquals(10, minimaxVideoDuration("MiniMax-Hailuo-2.3", 12))
    }

    @Test
    fun `no requested duration means no duration field at all`() {
        assertNull(minimaxVideoDuration("MiniMax-H3", null))
        assertNull(minimaxVideoDuration("MiniMax-Hailuo-2.3", null))
    }

    @Test
    fun `H3 maps the three shapes onto its own ratio enum`() {
        assertEquals("16:9", minimaxH3Ratio(ImageAspectRatio.LANDSCAPE))
        assertEquals("9:16", minimaxH3Ratio(ImageAspectRatio.PORTRAIT))
        assertEquals("1:1", minimaxH3Ratio(ImageAspectRatio.SQUARE))
    }

    // ---- classic body --------------------------------------------------------------------

    @Test
    fun `classic text-to-video sends the flat body and omits the frame`() {
        val body = buildMiniMaxVideoRequestBody(
            model = classic,
            prompt = "a cat",
            durationSeconds = null,
            firstFrameUrl = null,
        )
        assertEquals("MiniMax-Hailuo-2.3", body["model"]!!.jsonPrimitive.content)
        assertEquals("a cat", body["prompt"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("duration"))
        assertFalse(body.containsKey("first_frame_image"))
        // `resolution` is a quality tier, never derived from the aspect ratio.
        assertFalse(body.containsKey("resolution"))
    }

    @Test
    fun `classic image-to-video carries the first frame`() {
        val body = buildMiniMaxVideoRequestBody(
            model = classic,
            prompt = "a cat",
            durationSeconds = 10,
            firstFrameUrl = "data:image/png;base64,AAAA",
        )
        assertEquals(10, body["duration"]!!.jsonPrimitive.int)
        assertEquals("data:image/png;base64,AAAA", body["first_frame_image"]!!.jsonPrimitive.content)
    }

    // ---- H3 body -------------------------------------------------------------------------

    @Test
    fun `H3 text-to-video uses a multimodal content array and an explicit ratio`() {
        val body = buildMiniMaxH3VideoRequestBody(
            model = h3,
            prompt = "a cat",
            aspectRatio = ImageAspectRatio.PORTRAIT,
            durationSeconds = 6,
            firstFrameUrl = null,
        )
        val content = body["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("a cat", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("9:16", body["ratio"]!!.jsonPrimitive.content)
        assertEquals(6, body["duration"]!!.jsonPrimitive.int)
    }

    @Test
    fun `H3 image-to-video pins the ratio to adaptive`() {
        val body = buildMiniMaxH3VideoRequestBody(
            model = h3,
            prompt = "a cat",
            aspectRatio = ImageAspectRatio.PORTRAIT,
            durationSeconds = null,
            firstFrameUrl = "https://example.com/first.png",
        )
        val content = body["content"]!!.jsonArray
        assertEquals(2, content.size)
        val image = content[1].jsonObject
        assertEquals("image_url", image["type"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/first.png", image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("first_frame", image["role"]!!.jsonPrimitive.content)
        assertEquals("adaptive", body["ratio"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("duration"))
    }

    // ---- parsing -------------------------------------------------------------------------

    @Test
    fun `the create response yields the task id`() {
        val body = """{"task_id":"106916112212032","base_resp":{"status_code":0,"status_msg":"success"}}"""
        assertEquals("106916112212032", parseMiniMaxTaskId(body))
    }

    @Test
    fun `a rejected task hides in base_resp even with HTTP 200`() {
        val ok = """{"task_id":"t","base_resp":{"status_code":0,"status_msg":"success"}}"""
        assertNull(parseMiniMaxBaseRespMessage(ok))

        val bad = """{"task_id":"","base_resp":{"status_code":1004,"status_msg":"invalid api key"}}"""
        assertEquals("invalid api key", parseMiniMaxBaseRespMessage(bad))
    }

    @Test
    fun `the classic query response yields status and file id`() {
        val body = """{"task_id":"t","status":"Success","file_id":"176844028768320","base_resp":{"status_code":0,"status_msg":"success"}}"""
        assertEquals("Success", parseMiniMaxTaskStatus(body))
        assertEquals("176844028768320", parseMiniMaxFileId(body))
    }

    @Test
    fun `files retrieve yields the download url`() {
        val body = """{"file":{"file_id":"f","bytes":1,"download_url":"https://cdn/a.mp4"},"base_resp":{"status_code":0}}"""
        assertEquals("https://cdn/a.mp4", parseMiniMaxDownloadUrl(body))
    }

    @Test
    fun `the H3 query response is read from the task envelope`() {
        val body = """{"task":{"id":"t","status":"succeeded","content":{"url":"https://cdn/h3.mp4"},"error":null}}"""
        assertEquals("succeeded", parseMiniMaxH3TaskStatus(body))
        assertEquals("https://cdn/h3.mp4", parseMiniMaxH3VideoUrl(body))
    }

    @Test
    fun `a failed H3 task surfaces its error message`() {
        val body = """{"task":{"id":"t","status":"failed","error":{"message":"content blocked"}}}"""
        assertEquals("failed", parseMiniMaxH3TaskStatus(body))
        assertEquals("content blocked", parseMiniMaxH3ErrorMessage(body))
    }

    @Test
    fun `a garbled body parses to null instead of throwing`() {
        assertNull(parseMiniMaxTaskId("<html>502</html>"))
        assertNull(parseMiniMaxTaskStatus(""))
        assertNull(parseMiniMaxDownloadUrl("not json"))
    }
}
