package com.clma.app.data.ai.transformers

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.datastore.Settings
import kotlin.uuid.Uuid
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [selectUncachedImageUrls], the pure selection that decides whether OCR
 * has any uncached work to do (and therefore whether to flash the "Recognizing
 * image..." status at all).
 */
class OcrTransformerTest {

    private fun userMessage(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.USER,
        parts = parts.toList(),
    )

    @Test
    fun `no images returns empty`() {
        val messages = listOf(userMessage(UIMessagePart.Text("hello")))
        val result = selectUncachedImageUrls(messages) { false }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `cached-only images return empty`() {
        val messages = listOf(
            userMessage(UIMessagePart.Image("file:///a.png")),
            userMessage(UIMessagePart.Image("file:///b.png")),
        )
        val result = selectUncachedImageUrls(messages) { true }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mixed cache state returns only misses`() {
        val messages = listOf(
            userMessage(UIMessagePart.Image("file:///cached.png")),
            userMessage(UIMessagePart.Image("file:///missing.png")),
        )
        val cached = setOf("file:///cached.png")
        val result = selectUncachedImageUrls(messages) { url -> url in cached }
        assertEquals(listOf("file:///missing.png"), result)
    }

    @Test
    fun `non-file urls are ignored`() {
        val messages = listOf(
            userMessage(UIMessagePart.Image("https://example.com/a.png")),
            userMessage(UIMessagePart.Image("file:///local.png")),
        )
        val result = selectUncachedImageUrls(messages) { false }
        assertEquals(listOf("file:///local.png"), result)
    }

    @Test
    fun `empty message list returns empty`() {
        val result = selectUncachedImageUrls(emptyList()) { false }
        assertTrue(result.isEmpty())
    }
    @Test
    fun `local OCR is opt in and survives settings serialization`() {
        val settings = Settings()
        assertFalse(settings.useLocalOcr)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(Settings.serializer(), settings.copy(useLocalOcr = true))
        assertTrue(json.decodeFromString(Settings.serializer(), encoded).useLocalOcr)
        assertFalse(json.decodeFromString(Settings.serializer(), "{}").useLocalOcr)
    }

    @Test
    fun `OCR cache is isolated by backend model prompt and image`() {
        val settings = Settings()
        val url = "file:///image.png"
        val key = ocrCacheKey(url, settings)
        assertEquals(key, ocrCacheKey(url, settings.copy()))
        assertNotEquals(key, ocrCacheKey(url, settings.copy(useLocalOcr = true)))
        assertNotEquals(key, ocrCacheKey(url, settings.copy(ocrModelId = Uuid.random())))
        assertNotEquals(key, ocrCacheKey(url, settings.copy(ocrPrompt = "other prompt")))
        assertNotEquals(key, ocrCacheKey("file:///other.png", settings))
    }
}
