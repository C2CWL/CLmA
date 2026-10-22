package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * D3 — the shape of the audio/video content blocks the OpenAI-compatible path now emits.
 *
 * The one thing these assertions protect is the *contract with the server*: which block type, which
 * field names, and which container token. They also pin the deliberate degradation — an unsupported
 * model gets a named placeholder rather than a silent drop, and nothing is read off disk when the
 * part cannot be sent.
 */
class OpenAIMultimodalPartsTest {

    private fun typeOf(json: JsonObject) = json["type"]?.jsonPrimitive?.content
    private fun textOf(json: JsonObject) = json["text"]?.jsonPrimitive?.content

    @Test
    fun `a supported audio part becomes an input_audio block`() {
        val block = OpenAIMultimodalParts.audioContentPart(
            supportsAudioInput = true,
            url = "file:///data/user/0/excp/files/upload/note.wav",
            encode = { "QUJD" },
        )

        assertEquals("input_audio", typeOf(block))
        val payload = block["input_audio"]!!.jsonObject
        assertEquals("wav", payload["format"]?.jsonPrimitive?.content)
        assertEquals("data:audio/wav;base64,QUJD", payload["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an unsupported audio part degrades to a named placeholder, and nothing is read`() {
        var read = false
        val block = OpenAIMultimodalParts.audioContentPart(
            supportsAudioInput = false,
            url = "file:///note.mp3",
            encode = {
                read = true
                "QUJD"
            },
        )

        assertFalse("the file must not be read for a model that cannot take it", read)
        assertEquals("text", typeOf(block))
        assertEquals(OpenAIMultimodalParts.AUDIO_UNSUPPORTED_PLACEHOLDER, textOf(block))
    }

    @Test
    fun `an unreadable audio part says so instead of vanishing`() {
        val block = OpenAIMultimodalParts.audioContentPart(
            supportsAudioInput = true,
            url = "file:///note.mp3",
            encode = { null },
        )

        assertEquals("text", typeOf(block))
        assertEquals(OpenAIMultimodalParts.AUDIO_UNREADABLE_PLACEHOLDER, textOf(block))
    }

    @Test
    fun `the audio format follows the file's own container`() {
        assertEquals("wav", OpenAIMultimodalParts.audioFormatOf("file:///x/rec.wav"))
        assertEquals("m4a", OpenAIMultimodalParts.audioFormatOf("file:///x/rec.m4a"))
        assertEquals("mp3", OpenAIMultimodalParts.audioFormatOf("file:///x/rec.MP3"))
    }

    @Test
    fun `an unknown or absent extension falls back to the encoder's container`() {
        assertEquals("mp3", OpenAIMultimodalParts.audioFormatOf("file:///x/rec"))
        assertEquals("mp3", OpenAIMultimodalParts.audioFormatOf("file:///x/rec.txt"))
    }

    @Test
    fun `a query string does not confuse the format`() {
        assertEquals("wav", OpenAIMultimodalParts.audioFormatOf("file:///x/rec.wav?t=1"))
    }

    @Test
    fun `a supported video part becomes a video_url block with the real mime`() {
        val block = OpenAIMultimodalParts.videoContentPart(
            supportsVideoInput = true,
            url = "file:///x/clip.mov",
            encode = { "QUJD" },
        )

        assertEquals("video_url", typeOf(block))
        assertEquals(
            "data:video/quicktime;base64,QUJD",
            block["video_url"]!!.jsonObject["url"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `an unknown video extension falls back to mp4`() {
        val block = OpenAIMultimodalParts.videoContentPart(true, "file:///x/clip.bin", encode = { "QUJD" })
        assertEquals(
            "data:video/mp4;base64,QUJD",
            block["video_url"]!!.jsonObject["url"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `an unsupported video part degrades to a named placeholder`() {
        var read = false
        val block = OpenAIMultimodalParts.videoContentPart(
            supportsVideoInput = false,
            url = "file:///clip.mp4",
            encode = {
                read = true
                "QUJD"
            },
        )

        assertFalse(read)
        assertEquals("text", typeOf(block))
        assertEquals(OpenAIMultimodalParts.VIDEO_UNSUPPORTED_PLACEHOLDER, textOf(block))
    }

    @Test
    fun `an unreadable video part says so`() {
        val block = OpenAIMultimodalParts.videoContentPart(true, "file:///clip.mp4", encode = { null })
        assertEquals(OpenAIMultimodalParts.VIDEO_UNREADABLE_PLACEHOLDER, textOf(block))
    }
}
