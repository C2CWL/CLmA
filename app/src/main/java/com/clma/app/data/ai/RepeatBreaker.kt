package com.clma.app.data.ai

/** Model guidance only; adapted from moxw (AGPL-3.0, 81bbb7e). LoopGuard alone decides execution. */
internal object RepeatBreaker {
    enum class Grade(val reminder: String) {
        NUDGE("This tool call is repeating. Before trying again, state what new information you expect."),
        ESCALATE("The loop guard refused a repeated call. Try a different approach, ask the user for what is missing, or report the partial result."),
        STOP("Repeated refusals are consuming this turn. Stop retrying and explain the blocker using the information you have."),
    }

    fun grade(decision: LoopGuardDecision, guardTrips: Int): Grade? = when {
        decision.block && guardTrips >= 2 -> Grade.STOP
        decision.block -> Grade.ESCALATE
        decision.priorOccurrences == 2 -> Grade.NUDGE
        else -> null
    }
}
