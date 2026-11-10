package com.clma.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProotShellRunnerTest {
    @Test
    fun `proxy variables are env arguments before shell and never command text`() {
        val root = Files.createTempDirectory("proot-proxy-test").toFile()
        try {
            val context =
                WorkspaceShellContext(
                    root = "test",
                    command = "printf '%s' done",
                    cwd = "",
                    filesDir = root,
                    linuxDir = root,
                    tempDir = root,
                    workingDir = root,
                    timeoutMillis = 1000,
                    proxyEnvironment = mapOf("https_proxy" to "http://localhost:7890"),
                )
            val runner = ProotShellRunner(root)
            val args = runner.buildCommand(context, File(root, "proot"))
            val proxyIndex = args.indexOf("https_proxy=http://localhost:7890")
            assertTrue(proxyIndex > args.indexOf("-i"))
            assertTrue(proxyIndex < args.indexOf(WorkspaceManager.rootfsShell(root)))
            assertEquals(context.command, args.last())
            val disabled = runner.buildCommand(context.copy(proxyEnvironment = emptyMap()), File(root, "proot"))
            assertFalse(disabled.any { it.startsWith("https_proxy=") })
        } finally {
            root.deleteRecursively()
        }
    }
}
