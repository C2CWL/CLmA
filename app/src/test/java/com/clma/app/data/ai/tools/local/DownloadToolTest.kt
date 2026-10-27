package com.clma.app.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadToolTest {

    // Success path & filename sanitization both require a real DownloadManager and
    // android.net.Uri.parse — instrumented test required for those branches.

    @Test(expected = IllegalStateException::class)
    fun `download_file throws when url is missing`() {
        // Tool calls error("url is required") -> IllegalStateException
        val tool = downloadTool(NULL_CONTEXT)
        execTool(tool, """{}""")
    }

    @Test
    fun `download_file blocks private and loopback targets`() {
        val tool = downloadTool(NULL_CONTEXT)
        listOf(
            "http://127.0.0.1/test",
            "http://192.168.1.1/file.zip",
            "http://[::1]/payload",
            "http://0.1.2.3/file",
        ).forEach { url ->
            val result = execTool(tool, """{"url":"$url"}""")
            assertTrue("Expected blocked_private_address for $url, got: $result", result.contains("blocked_private_address"))
        }
    }
}