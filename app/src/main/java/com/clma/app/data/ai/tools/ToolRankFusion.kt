package com.clma.app.data.ai.tools

/**
 * How many retrieved tools one turn receives, and in what order.
 *
 * `tool_search` ranks the catalogue lexically ([ToolCatalog.matchAll]), and that ranking is the
 * only channel: a query matches a tool when its characters appear in the name or the summary.
 * This file takes that ranking as given and decides how much of it a turn gets to see.
 *
 * The rest is pure bookkeeping — no coroutines, no files, no Android — so the contract is
 * unit-testable on a bare JVM.
 *
 * (There used to be a second, embedding-based channel here, fused with the lexical one by
 * reciprocal rank. It was removed together with the on-device embedding subsystem: the runtime
 * cost of a vector model on a phone bought less than the lexical ranking it was blended with, and
 * a tool the lexical match never found is still reachable through `tool_search` itself.)
 */
object ToolRankFusion {

    /**
     * How many tools one turn may receive without the model asking for them.
     *
     * Sized against the local surface rather than the MCP one: an assistant that has opted into
     * the catalogue typically exposes one or two dozen local tools, so this covers the tools a
     * request plausibly needs while still leaving the bulk of a large MCP server unpinned.
     */
    const val DEFAULT_TURN_TOOL_BUDGET = 10

    /**
     * The names a turn's tool surface is built from: pinned and explicitly activated first — in
     * the order they were given, because that ordering is a deliberate statement — then the
     * retrieved ranking, of which at most [budget] are taken.
     *
     * The budget caps the *guess*, and only the guess. A pinned tool is the user saying "always
     * have this one ready", and one the model opened is the model saying the same thing; dropping
     * either to stay under a number would break exactly the promise it makes. So the surface can
     * legitimately exceed [budget] — that is what the pins are for.
     *
     * A name that is already pinned or activated does not consume the guess budget when it also
     * appears in [ranked]: it was going to be there anyway.
     */
    fun attachedForTurn(
        ranked: List<String>,
        pinned: List<String>,
        activated: List<String>,
        budget: Int = DEFAULT_TURN_TOOL_BUDGET,
    ): List<String> {
        val ordered = LinkedHashSet<String>()
        pinned.forEach { ordered += it }
        activated.forEach { ordered += it }
        var guessed = 0
        for (name in ranked) {
            if (guessed >= budget) break
            if (ordered.add(name)) guessed++
        }
        return ordered.toList()
    }
}