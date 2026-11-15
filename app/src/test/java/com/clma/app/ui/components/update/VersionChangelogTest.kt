package com.clma.app.ui.components.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionChangelogTest {
    @Test fun `first open shows notes`() {
        assertTrue(shouldShowChangelog("68", null))
    }
    @Test fun `only the dismissed version is suppressed`() {
        assertFalse(shouldShowChangelog("68", "68"))
        assertTrue(shouldShowChangelog("69", "68"))
    }
}
