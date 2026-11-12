package com.clma.app.data.ai.limits

import com.clma.app.data.usage.UsagePurpose

/** Reserve one existing model call for a report, not another batch of tools. */
internal object SubAgentFinalStepPolicy {
    fun isFinalStep(purpose: UsagePurpose?, stepIndex: Int, maxSteps: Int): Boolean =
        purpose == UsagePurpose.SUBAGENT && stepIndex == maxSteps - 1

    const val INSTRUCTION = "This is the final permitted model call for this sub-agent run. " +
        "Tools are unavailable. Return a concise plain-text report using only evidence already obtained. " +
        "State findings, unfinished work and limits explicitly; if the task is incomplete, say so. " +
        "Do not request tools or claim unperformed work."
}
