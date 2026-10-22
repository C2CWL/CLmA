package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ImageAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-33b — bare-JVM coverage for [VideoGenerationLogic]. */
class VideoGenerationLogicTest {

    // ---------------------------------------------------------------- aspect ratio

    @Test
    fun `blank aspect ratio defaults to landscape`() {
        assertEquals(ImageAspectRatio.LANDSCAPE, parseVideoAspectRatio(null))
        assertEquals(ImageAspectRatio.LANDSCAPE, parseVideoAspectRatio(""))
        assertEquals(ImageAspectRatio.LANDSCAPE, parseVideoAspectRatio("   "))
    }

    @Test
    fun `recognised aspect ratio tokens map to the three ratios`() {
        assertEquals(ImageAspectRatio.LANDSCAPE, parseVideoAspectRatio("landscape"))
        assertEquals(ImageAspectRatio.LANDSCAPE, parseVideoAspectRatio("16:9"))
        assertEquals(ImageAspectRatio.LANDSCAPE, parseVideoAspectRatio("WIDE"))
        assertEquals(ImageAspectRatio.PORTRAIT, parseVideoAspectRatio("9:16"))
        assertEquals(ImageAspectRatio.PORTRAIT, parseVideoAspectRatio(" vertical "))
        assertEquals(ImageAspectRatio.SQUARE, parseVideoAspectRatio("1:1"))
    }

    @Test
    fun `unknown aspect ratio is rejected rather than silently defaulted`() {
        assertNull(parseVideoAspectRatio("panorama"))
    }

    // ---------------------------------------------------------------- duration

    @Test
    fun `duration is null when unset and clamped into range otherwise`() {
        assertNull(clampVideoDuration(null))
        assertEquals(MIN_VIDEO_DURATION_SECONDS, clampVideoDuration(0))
        assertEquals(MIN_VIDEO_DURATION_SECONDS, clampVideoDuration(-5))
        assertEquals(5, clampVideoDuration(5))
        assertEquals(MAX_VIDEO_DURATION_SECONDS, clampVideoDuration(120))
    }

    // ---------------------------------------------------------------- model selection

    private val models = listOf(
        VideoModelChoice(id = "uuid-a", modelId = "wan2.2-t2v-plus", displayName = "Wan T2V Plus"),
        VideoModelChoice(id = "uuid-b", modelId = "doubao-seedance-1-0-pro-250528", displayName = "Seedance Pro"),
    )

    @Test
    fun `configured model is used when no override is requested`() {
        assertEquals(1, selectVideoModelIndex(models, configuredId = "uuid-b", requested = null))
        assertEquals(1, selectVideoModelIndex(models, configuredId = "uuid-b", requested = "  "))
    }

    @Test
    fun `requested model matches by model id then by display name, case-insensitively`() {
        assertEquals(0, selectVideoModelIndex(models, "uuid-b", "WAN2.2-T2V-PLUS"))
        assertEquals(1, selectVideoModelIndex(models, "uuid-a", "seedance pro"))
        assertEquals(1, selectVideoModelIndex(models, "uuid-a", "doubao-seedance-1-0-pro-250528"))
    }

    @Test
    fun `unknown request falls back to the configured model`() {
        assertEquals(0, selectVideoModelIndex(models, configuredId = "uuid-a", requested = "does-not-exist"))
    }

    @Test
    fun `nothing resolves without a usable model`() {
        assertEquals(-1, selectVideoModelIndex(models, configuredId = null, requested = "nope"))
        assertEquals(-1, selectVideoModelIndex(emptyList(), configuredId = "uuid-a", requested = null))
    }

    // ---------------------------------------------------------------- filenames

    @Test
    fun `path separators in a model name stay a single filename segment`() {
        assertEquals("a_b", sanitizeVideoFilenameComponent("a/b"))
        assertEquals("weird_model", sanitizeVideoFilenameComponent("weird\\model"))
    }

    @Test
    fun `filename follows the gallery shape with an mp4 extension`() {
        assertEquals("1700000000000_Wan_0.mp4", videoGenFilename("Wan", 1_700_000_000_000L, 0))
        assertEquals("videos/x.mp4", videoGalleryRelativePath("x.mp4"))
    }

    // ---------------------------------------------------------------- envelope

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `success envelope reports tool, model, prompt and per-video metadata`() {
        val json = parse(
            buildVideoGenEnvelope(
                tool = "generate_video",
                prompt = "a drone shot over a lake",
                model = "Wan T2V Plus",
                videos = listOf(GeneratedVideoInfo(path = "/data/v/1.mp4", sizeBytes = 42L, mimeType = "video/mp4")),
                modelCanSeeVideos = true,
            )
        )

        assertEquals("true", json["success"]?.jsonPrimitive?.content)
        assertEquals("generate_video", json["tool"]?.jsonPrimitive?.content)
        assertEquals("Wan T2V Plus", json["model"]?.jsonPrimitive?.content)
        assertEquals("1", json["count"]?.jsonPrimitive?.content)
        val videos = json["videos"]!!.jsonArray
        assertEquals(1, videos.size)
        assertEquals("/data/v/1.mp4", videos[0].jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals("video/mp4", videos[0].jsonObject["mime_type"]?.jsonPrimitive?.content)
        assertFalse(json.containsKey("visible_to_you"))
    }

    @Test
    fun `models without video input are told they cannot watch the result`() {
        val json = parse(
            buildVideoGenEnvelope(
                tool = "generate_video",
                prompt = "x",
                model = "Wan",
                videos = listOf(GeneratedVideoInfo("/data/v/1.mp4", 1L, "video/mp4")),
                modelCanSeeVideos = false,
            )
        )

        assertEquals("false", json["visible_to_you"]?.jsonPrimitive?.content)
        assertTrue(json["note"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `error envelope carries code and detail`() {
        val json = parse(buildVideoGenErrorEnvelope("missing_prompt", "`prompt` is required."))
        assertEquals("missing_prompt", json["error"]?.jsonPrimitive?.content)
        assertEquals("`prompt` is required.", json["detail"]?.jsonPrimitive?.content)
    }

    // ---------------------------------------------------------------- count

    @Test
    fun `count defaults to one and is clamped into range`() {
        assertEquals(1, clampVideoCount(null))
        assertEquals(1, clampVideoCount(0))
        assertEquals(1, clampVideoCount(-2))
        assertEquals(3, clampVideoCount(3))
        assertEquals(MAX_VIDEO_GEN_COUNT, clampVideoCount(99))
    }

    // ---------------------------------------------------------------- mode

    @Test
    fun `envelope reports image_to_video and the first frame when one was used`() {
        val json = parse(
            buildVideoGenEnvelope(
                tool = "generate_video",
                prompt = "p",
                model = "Wan",
                videos = listOf(GeneratedVideoInfo("/data/v/1.mp4", 1L, "video/mp4")),
                modelCanSeeVideos = true,
                firstFrame = "/data/i/frame.png",
            )
        )

        assertEquals("image_to_video", json["mode"]?.jsonPrimitive?.content)
        assertEquals("/data/i/frame.png", json["first_frame"]?.jsonPrimitive?.content)
    }

    @Test
    fun `envelope reports text_to_video with no frame key`() {
        val json = parse(buildVideoGenEnvelope("generate_video", "p", "Wan", emptyList(), true))
        assertEquals("text_to_video", json["mode"]?.jsonPrimitive?.content)
        assertFalse(json.containsKey("first_frame"))
    }
}
