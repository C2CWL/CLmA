package com.clma.app.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessToolApprovalPolicyParkTest {

    @Test
    fun `sending sms and writing memory require a human`() {
        assertTrue(HeadlessToolApprovalPolicy.needsHumanToRun("send_sms", "{}"))
        assertTrue(HeadlessToolApprovalPolicy.needsHumanToRun("memory_write", "{}"))
    }

    @Test
    fun `web search can run unattended`() {
        assertFalse(HeadlessToolApprovalPolicy.needsHumanToRun("web_search", "{}"))
    }
}
