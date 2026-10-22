package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure decision logic and the two request bodies of the DashScope image-**edit** path,
 * mirroring `DashScopeImageRequest`'s split: everything decidable without a socket lives in the
 * helper file so a bare-JVM test can fix its shape.
 */
class DashScopeImageEditRequestTest {

    // ---- endpoint selection --------------------------------------------------------------

    @Test
    fun `the qwen image edit family is served by the multimodal endpoint`() {
        assertTrue(dashScopeUsesMultimodalEditEndpoint("qwen-image-edit"))
        assertTrue(dashScopeUsesMultimodalEditEndpoint("qwen-image-edit-plus"))
        assertTrue(dashScopeUsesMultimodalEditEndpoint("qwen-image-edit-max"))
        // Case and surrounding whitespace must not decide the endpoint.
        assertTrue(dashScopeUsesMultimodalEditEndpoint("  Qwen-Image-Edit-Plus  "))
    }

    @Test
    fun `the qwen image 2x and 3x generation line edits on the same endpoint`() {
        assertTrue(dashScopeUsesMultimodalEditEndpoint("qwen-image-2.0-pro"))
        assertTrue(dashScopeUsesMultimodalEditEndpoint("qwen-image-3.0"))
    }

    @Test
    fun `wan 26 image and the wan 25 i2i preview edit on the multimodal endpoint`() {
        assertTrue(dashScopeUsesMultimodalEditEndpoint("wan2.6-image"))
        assertTrue(dashScopeUsesMultimodalEditEndpoint("wan2.5-i2i-preview"))
    }

    @Test
    fun `the legacy wanx imageedit family is not the multimodal endpoint`() {
        assertFalse(dashScopeUsesMultimodalEditEndpoint("wanx2.1-imageedit"))
        assertTrue(dashScopeUsesLegacyEditEndpoint("wanx2.1-imageedit"))
    }

    @Test
    fun `a plain text to image model is neither edit family`() {
        assertFalse(dashScopeUsesMultimodalEditEndpoint("qwen-image-plus"))
        assertFalse(dashScopeUsesLegacyEditEndpoint("qwen-image-plus"))
        // ... and so starts on the multimodal endpoint, where the retry can correct it.
        assertTrue(dashScopePrefersMultimodalEdit("qwen-image-plus"))
    }

    @Test
    fun `only the legacy family flips the preferred endpoint`() {
        assertFalse(dashScopePrefersMultimodalEdit("wanx2.1-imageedit"))
        assertTrue(dashScopePrefersMultimodalEdit("qwen-image-edit-max"))
    }

    // ---- multimodal body -----------------------------------------------------------------

    @Test
    fun `the multimodal edit body carries every source image then the instruction`() {
        val body = buildDashScopeMultimodalEditRequestBody(
            model = Model(modelId = "qwen-image-edit-plus"),
            prompt = "make the sky a sunset",
            imageDataUris = listOf("data:image/png;base64,AAA", "data:image/png;base64,BBB"),
            numOfImages = 2,
        ).jsonObject

        assertEquals("qwen-image-edit-plus", body["model"]?.jsonPrimitive?.content)

        val message = body["input"]!!.jsonObject["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]?.jsonPrimitive?.content)

        val content = message["content"]!!.jsonArray
        assertEquals(3, content.size)
        assertEquals("data:image/png;base64,AAA", content[0].jsonObject["image"]?.jsonPrimitive?.content)
        assertEquals("data:image/png;base64,BBB", content[1].jsonObject["image"]?.jsonPrimitive?.content)
        assertEquals("make the sky a sunset", content[2].jsonObject["text"]?.jsonPrimitive?.content)

        assertEquals(2, body["parameters"]!!.jsonObject["n"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `the multimodal edit body sends no size so the output keeps the source aspect`() {
        val parameters = buildDashScopeMultimodalEditRequestBody(
            model = Model(modelId = "qwen-image-edit-plus"),
            prompt = "x",
            imageDataUris = listOf("data:image/png;base64,AAA"),
            numOfImages = 1,
        ).jsonObject["parameters"]!!.jsonObject

        assertNull(parameters["size"])
    }

    // ---- legacy body ---------------------------------------------------------------------

    @Test
    fun `the legacy edit body uses description_edit over a base image url`() {
        val body = buildDashScopeEditRequestBody(
            model = Model(modelId = "wanx2.1-imageedit"),
            prompt = "Blue background, yellow leaves.",
            baseImageUri = "data:image/jpeg;base64,ZZZ",
            numOfImages = 1,
        ).jsonObject

        assertEquals("wanx2.1-imageedit", body["model"]?.jsonPrimitive?.content)

        val input = body["input"]!!.jsonObject
        assertEquals("description_edit", input["function"]?.jsonPrimitive?.content)
        assertEquals("Blue background, yellow leaves.", input["prompt"]?.jsonPrimitive?.content)
        assertEquals("data:image/jpeg;base64,ZZZ", input["base_image_url"]?.jsonPrimitive?.content)

        assertEquals(1, body["parameters"]!!.jsonObject["n"]?.jsonPrimitive?.content?.toInt())
    }
}
