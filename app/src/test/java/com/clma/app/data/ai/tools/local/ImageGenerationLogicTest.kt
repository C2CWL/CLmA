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

/** P2-33 — bare-JVM coverage for [ImageGenerationLogic]. */
class ImageGenerationLogicTest {

    // ---------------------------------------------------------------- aspect ratio

    @Test
    fun `blank aspect ratio defaults to square`() {
        assertEquals(ImageAspectRatio.SQUARE, parseImageAspectRatio(null))
        assertEquals(ImageAspectRatio.SQUARE, parseImageAspectRatio(""))
        assertEquals(ImageAspectRatio.SQUARE, parseImageAspectRatio("   "))
    }

    @Test
    fun `recognised aspect ratio tokens map to the three ratios`() {
        assertEquals(ImageAspectRatio.SQUARE, parseImageAspectRatio("square"))
        assertEquals(ImageAspectRatio.SQUARE, parseImageAspectRatio("1:1"))
        assertEquals(ImageAspectRatio.LANDSCAPE, parseImageAspectRatio("LANDSCAPE"))
        assertEquals(ImageAspectRatio.LANDSCAPE, parseImageAspectRatio("16:9"))
        assertEquals(ImageAspectRatio.PORTRAIT, parseImageAspectRatio(" 9:16 "))
        assertEquals(ImageAspectRatio.PORTRAIT, parseImageAspectRatio("portrait"))
    }

    @Test
    fun `unknown aspect ratio is rejected rather than silently defaulted`() {
        assertNull(parseImageAspectRatio("panorama"))
    }

    // ---------------------------------------------------------------- count

    @Test
    fun `count defaults to one and is clamped into range`() {
        assertEquals(1, clampImageCount(null))
        assertEquals(1, clampImageCount(0))
        assertEquals(1, clampImageCount(-3))
        assertEquals(3, clampImageCount(3))
        assertEquals(MAX_IMAGE_GEN_COUNT, clampImageCount(99))
    }

    // ---------------------------------------------------------------- model selection

    private val models = listOf(
        ImageModelChoice(id = "uuid-a", modelId = "google/gemini-image", displayName = "Gemini Image"),
        ImageModelChoice(id = "uuid-b", modelId = "qwen-image-3.0", displayName = "Qwen Image"),
    )

    @Test
    fun `configured model is used when no override is requested`() {
        assertEquals(1, selectImageModelIndex(models, configuredId = "uuid-b", requested = null))
        assertEquals(1, selectImageModelIndex(models, configuredId = "uuid-b", requested = "  "))
    }

    @Test
    fun `requested model matches by model id then by display name, case-insensitively`() {
        // by model id, case-insensitive (no dependence on the configured model)
        assertEquals(1, selectImageModelIndex(models, "uuid-a", "QWEN-IMAGE-3.0"))
        // by model id, exact
        assertEquals(0, selectImageModelIndex(models, "uuid-b", "google/gemini-image"))
        // by display name, case-insensitive
        assertEquals(0, selectImageModelIndex(models, "uuid-b", "gemini image"))
    }

    @Test
    fun `unknown request falls back to the configured model`() {
        assertEquals(1, selectImageModelIndex(models, configuredId = "uuid-b", requested = "does-not-exist"))
    }

    @Test
    fun `unknown request with no configured model resolves nothing`() {
        assertEquals(-1, selectImageModelIndex(models, configuredId = null, requested = "nope"))
        assertEquals(-1, selectImageModelIndex(emptyList(), configuredId = "uuid-b", requested = null))
    }

    // ---------------------------------------------------------------- filenames

    @Test
    fun `path separators in a model name stay a single filename segment`() {
        assertEquals("google_gemini-image", sanitizeImageFilenameComponent("google/gemini-image"))
        assertEquals("weird_model_name", sanitizeImageFilenameComponent("weird\\model\\name"))
        assertEquals("gpt-image-1", sanitizeImageFilenameComponent("gpt-image-1"))
    }

    @Test
    fun `filename follows the gallery shape`() {
        assertEquals("1700000000000_gpt-image-1_0.png", imageGenFilename("gpt-image-1", 1_700_000_000_000L, 0))
        assertEquals("images/x.png", imageGalleryRelativePath("x.png"))
    }

    // ---------------------------------------------------------------- envelope

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `success envelope reports tool, model, prompt, count and per-image metadata`() {
        val json = parse(
            buildImageGenEnvelope(
                tool = "generate_image",
                prompt = "a red panda",
                model = "Qwen Image",
                images = listOf(
                    GeneratedImageInfo(path = "/data/x/1.png", width = 1024, height = 1024, sizeBytes = 42L),
                    GeneratedImageInfo(path = "/data/x/2.png", width = 1536, height = 1024, sizeBytes = 84L),
                ),
                modelCanSeeImages = true,
            )
        )

        assertEquals("true", json["success"]?.jsonPrimitive?.content)
        assertEquals("generate_image", json["tool"]?.jsonPrimitive?.content)
        assertEquals("Qwen Image", json["model"]?.jsonPrimitive?.content)
        assertEquals("a red panda", json["prompt"]?.jsonPrimitive?.content)
        assertEquals("2", json["count"]?.jsonPrimitive?.content)
        val images = json["images"]!!.jsonArray
        assertEquals(2, images.size)
        assertEquals("/data/x/2.png", images[1].jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals("1536", images[1].jsonObject["width"]?.jsonPrimitive?.content)
        assertFalse(json.containsKey("visible_to_you"))
    }

    @Test
    fun `text-only models are told they cannot see the result`() {
        val json = parse(
            buildImageGenEnvelope(
                tool = "edit_image",
                prompt = "make it night",
                model = "Qwen Image",
                images = listOf(GeneratedImageInfo("/data/x/1.png", 512, 512, 1L)),
                modelCanSeeImages = false,
            )
        )

        assertEquals("false", json["visible_to_you"]?.jsonPrimitive?.content)
        assertTrue(json["note"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `error envelope carries code and detail`() {
        val json = parse(buildImageGenErrorEnvelope("missing_prompt", "`prompt` is required."))
        assertEquals("missing_prompt", json["error"]?.jsonPrimitive?.content)
        assertEquals("`prompt` is required.", json["detail"]?.jsonPrimitive?.content)
    }
}
