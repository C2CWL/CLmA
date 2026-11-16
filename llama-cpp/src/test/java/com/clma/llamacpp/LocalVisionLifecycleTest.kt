package com.clma.llamacpp

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisionLifecycleTest {
    private val vision = "Qwen3VL-2B-Instruct-Q8_0.gguf"

    @Test fun `only visual catalog models require media`() {
        assertTrue(LocalVisionLifecycle.requiresMedia(vision))
        assertFalse(LocalVisionLifecycle.requiresMedia("Qwen3-0.6B-Q8_0.gguf"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `plain chat cannot load the visual model`() {
        LocalVisionLifecycle.validateRequest(vision, listOf(UIMessage.user("hello")))
    }

    @Test fun `image and video authorize a visual request`() {
        listOf(UIMessagePart.Image("file:///image.png"), UIMessagePart.Video("file:///clip.mp4")).forEach {
            LocalVisionLifecycle.validateRequest(vision, listOf(UIMessage(role = MessageRole.USER, parts = listOf(it))))
        }
    }

    @Test fun `text models keep their normal text behavior`() {
        LocalVisionLifecycle.validateRequest("Qwen3-0.6B-Q8_0.gguf", listOf(UIMessage.user("hello")))
    }

    @Test fun `file video opens by absolute path`() {
        val source = java.io.File("src/main/java/com/clma/llamacpp/LocalVideoFrames.kt").readText()
        val start = source.indexOf("val path = when (uri.scheme)")
        assertTrue(start >= 0)
        val block = source.substring(start, start + 420)
        assertTrue(block.contains("\"file\" -> requireNotNull(uri.path)"))
        assertTrue(block.contains("retriever.setDataSource(path)"))
        assertTrue(block.contains("extractor.setDataSource(path)"))
    }

    @Test fun `vision graph callback uses the same cancellation flag`() {
        val source = java.io.File("src/main/cpp/llama_jni.cpp").readText()
        val start = source.indexOf("params.cb_eval")
        assertTrue(start >= 0)
        val callback = source.substring(start, start + 180)
        assertTrue(callback.contains("return !abortIfCancelled(data)"))
    }
}
