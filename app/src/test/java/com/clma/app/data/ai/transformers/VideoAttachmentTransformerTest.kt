package com.clma.app.data.ai.transformers

import com.clma.app.data.ai.tools.LocalToolOption
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VideoAttachmentTransformerTest {
    @Test fun `fallback respects group enablement and individual opt out`() {
        assertFalse(videoFallbackEnabled(emptyList(), emptySet()))
        assertTrue(videoFallbackEnabled(listOf(LocalToolOption.LocalVision), emptySet()))
        assertFalse(videoFallbackEnabled(listOf(LocalToolOption.LocalVision), setOf("recognize_local_media")))
    }

    @Test fun `unsupported video retains exact attachment path and access recovery`() {
        val path = "content://media/external/video/media/42"
        val notice = Json.parseToJsonElement(videoAttachmentNotice(path, null, false)).jsonObject
        assertEquals(path, notice["path"]!!.jsonPrimitive.content)
        assertFalse(notice["native_video_input"]!!.jsonPrimitive.boolean)
        assertTrue(notice["recovery"]!!.jsonPrimitive.content.contains("enable"))
    }

    @Test fun `local evidence stays explicitly untrusted with no repeated retries`() {
        val failure = "{\"error\":\"permission_denied\"}"
        val notice = Json.parseToJsonElement(videoAttachmentNotice("/upload/a.mp4", failure, true)).jsonObject
        assertEquals(failure, notice["local_recognition_result"]!!.jsonPrimitive.content)
        assertTrue(notice["trust"]!!.jsonPrimitive.content.contains("Do not retry"))
    }
}
