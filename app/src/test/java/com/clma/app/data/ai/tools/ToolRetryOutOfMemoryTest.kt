package com.clma.app.data.ai.tools

import com.clma.ai.util.RequestTooLargeException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRetryOutOfMemoryTest {
    @Test
    fun `out of memory is never a transient tool failure`() {
        assertFalse(ToolExecutionRetryPolicy.isTransientFailure(OutOfMemoryError("Failed to allocate")))
        assertFalse(ToolExecutionRetryPolicy.isTransientFailure(IOException("io", OutOfMemoryError())))
        assertFalse(ToolExecutionRetryPolicy.isTransientFailure(RequestTooLargeException("too large")))
    }

    @Test
    fun `a socket failure still is`() {
        assertTrue(ToolExecutionRetryPolicy.isTransientFailure(java.net.SocketTimeoutException("timeout")))
    }
}
