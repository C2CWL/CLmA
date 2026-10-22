package com.clma.ai.util

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTooLargeTest {
    @Test
    fun `out of memory anywhere in the cause chain is a request too large`() {
        val oom = OutOfMemoryError("Failed to allocate a 88571288 byte allocation")
        assertTrue(oom.isRequestTooLarge())
        assertTrue(IOException("wrapped", oom).isRequestTooLarge())
        assertTrue(RuntimeException("outer", IOException("inner", oom)).isRequestTooLarge())
        assertTrue(RequestTooLargeException("pre-send refusal").isRequestTooLarge())
    }

    @Test
    fun `ordinary failures are not`() {
        assertFalse(IOException("connection reset").isRequestTooLarge())
        assertFalse(IOException("connection reset").isFatalForRetry())
        assertFalse(IllegalStateException("x").isFatalForRetry())
    }

    @Test
    fun `any virtual machine error is fatal for retry but only OOM means too large`() {
        val soe = StackOverflowError()
        assertTrue(soe.isFatalForRetry())
        assertTrue(IOException("wrapped", soe).isFatalForRetry())
        assertFalse(soe.isRequestTooLarge())
    }

    @Test
    fun `an oversized video is fatal for retry but compressing cannot fix it`() {
        val media = MediaTooLargeException("clip.mp4", 30L * 1024 * 1024)
        assertTrue(media.isFatalForRetry())
        assertTrue(media.isMediaTooLarge())
        assertFalse(media.isRequestTooLarge())
    }
}
