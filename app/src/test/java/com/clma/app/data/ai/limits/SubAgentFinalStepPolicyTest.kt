package com.clma.app.data.ai.limits

import com.clma.app.data.usage.UsagePurpose
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentFinalStepPolicyTest {
    @Test
    fun `only final existing subagent step is reserved`() {
        for (step in 0 until 11) {
            assertFalse(SubAgentFinalStepPolicy.isFinalStep(UsagePurpose.SUBAGENT, step, 12))
        }
        assertTrue(SubAgentFinalStepPolicy.isFinalStep(UsagePurpose.SUBAGENT, 11, 12))
        assertTrue(SubAgentFinalStepPolicy.isFinalStep(UsagePurpose.SUBAGENT, 0, 1))
    }

    @Test
    fun `ordinary peer cron and other purposes are unaffected`() {
        assertFalse(SubAgentFinalStepPolicy.isFinalStep(null, 11, 12))
        UsagePurpose.entries.filter { it != UsagePurpose.SUBAGENT }.forEach {
            assertFalse(SubAgentFinalStepPolicy.isFinalStep(it, 11, 12))
        }
    }
}
