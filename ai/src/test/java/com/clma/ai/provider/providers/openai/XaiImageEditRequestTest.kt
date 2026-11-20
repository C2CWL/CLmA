package com.clma.ai.provider.providers.openai

import com.clma.ai.provider.CustomBody
import com.clma.ai.provider.ImageEditParams
import com.clma.ai.provider.Model
import com.clma.ai.ui.ImageAspectRatio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the xAI image-edit body. The live API rejects OpenAI multipart uploads with HTTP 415 and
 * requires `application/json`; these cases keep that shape from drifting back.
 */
class XaiImageEditRequestTest {
    @Test
    fun `only the official api host is treated as xai`() {
        assertTrue(isXaiBaseUrl("https://api.x.ai/v1"))
        assertTrue(isXaiBaseUrl("https://api.x.ai/v1/"))
        assertFalse(isXaiBaseUrl("https://api.x.ai.example.com/v1"))
        assertFalse(isXaiBaseUrl("https://proxy.example/v1"))
        assertFalse(isXaiBaseUrl("not a url"))
    }

    @Test
    fun `a single source uses the image object and json bytes`() {
        val body = buildXaiImageEditJson(
            params("grok-imagine-image"),
            listOf("data:image/png;base64,AAA"),
        )

        assertEquals("grok-imagine-image", body["model"]?.jsonPrimitive?.content)
        assertEquals("make it a sketch", body["prompt"]?.jsonPrimitive?.content)
        assertEquals("16:9", body["aspect_ratio"]?.jsonPrimitive?.content)
        assertEquals("b64_json", body["response_format"]?.jsonPrimitive?.content)
        assertNull(body["images"])
        assertNull(body["size"])
        val image = body["image"]!!.jsonObject
        assertEquals("data:image/png;base64,AAA", image["url"]?.jsonPrimitive?.content)
        assertEquals("image_url", image["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `several sources use images and stay mutually exclusive with image`() {
        val body = buildXaiImageEditJson(
            params("grok-imagine-image", ImageAspectRatio.PORTRAIT, 12),
            listOf("data:image/jpeg;base64,AAA", "data:image/webp;base64,BBB"),
        )

        assertEquals("9:16", body["aspect_ratio"]?.jsonPrimitive?.content)
        assertEquals(10, body["n"]?.jsonPrimitive?.content?.toInt())
        assertNull(body["image"])
        val images = body["images"]!!.jsonArray
        assertEquals(2, images.size)
        assertEquals("data:image/jpeg;base64,AAA", images[0].jsonObject["url"]?.jsonPrimitive?.content)
        assertEquals("data:image/webp;base64,BBB", images[1].jsonObject["url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a custom body can override the response format and add a resolution`() {
        val body = buildXaiImageEditJson(
            params("grok-imagine-image").copy(
                customBody = listOf(
                    CustomBody("response_format", JsonPrimitive("url")),
                    CustomBody("resolution", JsonPrimitive("2k")),
                    CustomBody("image", buildJsonObject { put("url", "https://evil.example/x.png") }),
                    CustomBody("size", JsonPrimitive("1024x1024")),
                ),
            ),
            listOf("data:image/png;base64,AAA"),
        )

        assertEquals("url", body["response_format"]?.jsonPrimitive?.content)
        assertEquals("2k", body["resolution"]?.jsonPrimitive?.content)
        assertNull(body["size"])
        assertEquals(
            "data:image/png;base64,AAA",
            body["image"]!!.jsonObject["url"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `zero or more than five sources are refused before a request is built`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildXaiImageEditJson(params("grok-imagine-image"), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildXaiImageEditJson(params("grok-imagine-image"), List(6) { "data:image/png;base64,A" })
        }
    }

    @Test
    fun `a local file becomes a data uri inside an application json body`() {
        val file = File.createTempFile("xai-edit", ".png")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val body = buildXaiImageEditRequestBody(
                params("grok-imagine-image").copy(images = listOf(file.absolutePath)),
            )
            assertEquals("application/json", body.contentType().toString())
            val buffer = Buffer()
            body.writeTo(buffer)
            val json = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val url = json["image"]!!.jsonObject["url"]!!.jsonPrimitive.content
            assertTrue(url.startsWith("data:image/png;base64,"))
            assertFalse(url.contains("multipart"))
        } finally {
            file.delete()
        }
    }

    private fun params(
        modelId: String,
        ratio: ImageAspectRatio = ImageAspectRatio.LANDSCAPE,
        count: Int = 1,
    ) = ImageEditParams(
        model = Model(modelId = modelId),
        prompt = "make it a sketch",
        images = listOf("/tmp/source.png"),
        numOfImages = count,
        aspectRatio = ratio,
    )
}
