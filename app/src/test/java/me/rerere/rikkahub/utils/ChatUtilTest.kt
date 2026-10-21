package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUtilTest {
    @Test
    fun `allows agc camera configs as text documents`() {
        assertTrue(isAllowedFileType("camera-config.agc", "application/octet-stream"))
        assertTrue(isAllowedFileType("CAMERA-CONFIG.AGC", "application/octet-stream"))
    }

    @Test
    fun `unknown binary types are not inlined as text but still classifiable as documents`() {
        // isAllowedFileType now means "inline as text", not "may upload".
        assertFalse(isAllowedFileType("archive.unknown", "application/octet-stream"))
        assertFalse(isAllowedFileType("app.apk", "application/vnd.android.package-archive"))
        assertEquals(
            ChatAttachmentKind.DOCUMENT,
            classifyChatAttachment("app.apk", "application/vnd.android.package-archive"),
        )
        assertEquals(
            ChatAttachmentKind.DOCUMENT,
            classifyChatAttachment("archive.zip", "application/zip"),
        )
    }

    @Test
    fun `classifies media by mime and extension`() {
        assertEquals(ChatAttachmentKind.IMAGE, classifyChatAttachment("a.png", "image/png"))
        assertEquals(ChatAttachmentKind.IMAGE, classifyChatAttachment("a.PNG", "application/octet-stream"))
        assertEquals(ChatAttachmentKind.VIDEO, classifyChatAttachment("clip.mp4", "video/mp4"))
        assertEquals(ChatAttachmentKind.AUDIO, classifyChatAttachment("note.m4a", "audio/mp4"))
        assertEquals(ChatAttachmentKind.DOCUMENT, classifyChatAttachment("notes.txt", "text/plain"))
        assertEquals(ChatAttachmentKind.DOCUMENT, classifyChatAttachment("report.pdf", "application/pdf"))
    }

    @Test
    fun `toMessagePart builds the right UIMessagePart`() {
        val image = ChatAttachmentKind.IMAGE.toMessagePart("file:///a.png", "a.png", "image/png")
        val doc = ChatAttachmentKind.DOCUMENT.toMessagePart(
            "file:///app.apk",
            "app.apk",
            "application/vnd.android.package-archive",
        )
        assertTrue(image is me.rerere.ai.ui.UIMessagePart.Image)
        assertTrue(doc is me.rerere.ai.ui.UIMessagePart.Document)
        assertEquals("app.apk", (doc as me.rerere.ai.ui.UIMessagePart.Document).fileName)
    }
}
