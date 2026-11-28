package com.clma.app.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoSpeechResultTest {
    @Test
    fun `only explicit spoken dialogue is forwarded`() {
        assertEquals("你好", speechTranscript("""{"spoken":true,"transcript":" 你好 "}"""))
        assertNull(speechTranscript("""{"spoken":false,"transcript":"Music is playing"}"""))
        assertNull(speechTranscript("""{"spoken":"true","transcript":"Uncertain speech"}"""))
        assertNull(speechTranscript("""{"spoken":true,"transcript":"..."}"""))
        assertNull(speechTranscript("No one is speaking"))
    }

    @Test
    fun `recognizer may fence JSON and transcript is bounded`() {
        val raw = "```json\n{\"spoken\":true,\"transcript\":\"${"a".repeat(5_000)}\"}\n```"
        assertEquals(4_000, speechTranscript(raw)?.length)
    }
}
