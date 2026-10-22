package me.rerere.ai.provider.providers.openai

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins how a picked first-frame file is handed to the vendors that take an inline image. */
class VideoImagePayloadTest {

    @Test
    fun `the data prefix is stripped for the vendors that want bare base64`() {
        assertEquals("AAAA", inlineImagePayload("data:image/png;base64,AAAA"))
        assertEquals("AAAA", inlineImagePayload("data:image/jpeg;base64,AAAA"))
        assertEquals("AAAA", inlineImagePayload("DATA:image/webp;base64,AAAA"))
    }

    @Test
    fun `a public url is passed through untouched`() {
        assertEquals(
            "https://example.com/first.png",
            inlineImagePayload("https://example.com/first.png"),
        )
    }

    @Test
    fun `a data uri without a payload does not lose its value`() {
        assertEquals("data:image/png;base64", inlineImagePayload("data:image/png;base64"))
        assertEquals("", inlineImagePayload("data:image/png;base64,"))
    }
}
