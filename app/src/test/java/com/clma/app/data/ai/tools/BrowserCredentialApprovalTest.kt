package com.clma.app.data.ai.tools

import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCredentialApprovalTest {
    @Test
    fun `browser credential operations require per call approval`() {
        for (name in listOf("browser_cookie_import", "browser_cookie_export", "browser_site_clear")) {
            assertTrue(name, ToolApprovalDefaults.isHardFloor(name))
            assertTrue(name, name in ToolApprovalDefaults.DANGEROUS_TOOLS)
        }
    }
}
