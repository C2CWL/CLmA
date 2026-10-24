package com.clma.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootfsPatcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `patch replaces ubuntu deb822 sources and generates clma-init-tools script`() {
        val root = tmp.newFolder("linux")
        val etc = File(root, "etc").apply { mkdirs() }
        val sourcesDir = File(etc, "apt/sources.list.d").apply { mkdirs() }
        val ubuntuSources = File(sourcesDir, "ubuntu.sources").apply {
            writeText("URIs: http://ports.ubuntu.com/ubuntu-ports/\nSuites: noble\n")
        }

        val patcher = RootfsPatcher()
        patcher.patch(root)

        val updated = ubuntuSources.readText()
        assertTrue(updated.contains(RootfsPatcher.DEFAULT_UBUNTU_MIRROR))

        val script = File(root, "usr/local/bin/clma-init-tools")
        assertTrue(script.exists())
        assertTrue(script.readText().contains("ffmpeg"))
        assertTrue(script.readText().contains("imagemagick"))
        assertTrue(script.readText().contains("jq"))
        assertTrue(script.readText().contains("poppler-utils"))
    }
}
