package com.clma.app.subagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRetrySafetyTest {
    @Test fun `retry is allowed only before the first retry and before tool execution`() {
        assertTrue(shouldRetrySubAgent(attempt = 0, maxAttempts = 2, toolsAlreadyRan = false))
        assertFalse(shouldRetrySubAgent(attempt = 1, maxAttempts = 2, toolsAlreadyRan = false))
        assertFalse(shouldRetrySubAgent(attempt = 0, maxAttempts = 1, toolsAlreadyRan = false))
        assertFalse(shouldRetrySubAgent(attempt = 0, maxAttempts = 2, toolsAlreadyRan = true))
    }
}
