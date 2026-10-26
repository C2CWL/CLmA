package com.clma.app.data.ai.tools

/**
 * Phase 2 — the pure half of semantic tool retrieval.
 *
 * `tool_search` has always ranked lexically ([ToolCatalog.matchAll]): a query matches a tool when
 * its characters appear in the name or the summary. That is exact, free, and blind the moment the
 * model describes a tool instead of naming it — which is the normal case for a user who writes in
 * Chinese, where the query shares no characters with `scrape_web` at all and the lexical scorer
 * returns nothing.
 *
 * This file adds the other channel: rank the same catalogue by embedding similarity, fuse the two
 * rankings, and decide which tools a turn should be handed before the model speaks. Everything
 * here is pure — no coroutines, no files, no Android — so the ranking contract is unit-testable on
 * a bare JVM, and the impure parts (loading the model, caching vectors) stay in
 * [com.clma.app.data.vector.ToolVectorIndex].
 *
 * ## Why ranks, and not scores
 *
 * Embedding cosine similarities are high and tightly bunched: on this app's own baseline two
 * *unrelated* passages reach 0.78. An absolute cutoff therefore cannot separate relevant from
 * irrelevant, and no amount of tuning makes one. Reciprocal rank fusion sidesteps that entirely by
 * combining the two *orderings* and never the numbers — which is the only thing those numbers are
 * trustworthy about. It also means the lexical scorer's 100/40/10/4/2 weights never have to be
 * commensurable with a cosine in the first place.
 *
 * ## Why fusing is safe for the behaviour we already have
 *
 * RRF only ever reorders. A tool whose name *is* the query still ranks first lexically and stays
 * at the top of the fused list, so the exact-match behaviour `tool_search` already had survives
 * unchanged; the vector channel can only promote tools the lexical channel never found.
 */
object ToolRankFusion {

    /**
     * The RRF constant. 60 is the value from the original paper; it also flattens the top of each
     * list enough that a rank-1 hit in one channel cannot outvote a rank-2..4 consensus in the
     * other, which is the behaviour we want when the two channels disagree about the *order* of
     * the same few tools.
     */
    const val RRF_K = 60

    /**
     * How many tools one turn may receive without the model asking for them.
     *
     * Sized against the local surface rather than the MCP one: an assistant that has opted into
     * the catalogue typically exposes one or two dozen local tools, so this covers the tools a
     * request plausibly needs while still leaving the bulk of a large MCP server unpinned.
     */
    const val DEFAULT_TURN_TOOL_BUDGET = 10

    /**
     * Cosine ranking over already-normalised vectors.
     *
     * [docVectors] must be parallel to [keys]. A length mismatch returns an empty ranking rather
     * than a half-answer: the caller that mismatched them has a bug, and an empty result is
     * visible while a plausible-looking partial one is not.
     */
    fun rankByCosine(query: FloatArray, keys: List<String>, docVectors: List<FloatArray>): List<String> {
        if (keys.size != docVectors.size || keys.isEmpty()) return emptyList()
        return keys
            .mapIndexed { index, key -> key to dot(query, docVectors[index]) }
            .sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
            .map { it.first }
    }

    /**
     * Reciprocal rank fusion of two ranked name lists (best first).
     *
     * Ties are broken by the order the names first appear across the inputs, then alphabetically,
     * so the result is a total order: the same query against the same catalogue can never return
     * two different orderings, which is what makes the retrieval testable at all.
     */
    fun fuse(
        lexical: List<String>,
        vector: List<String>,
        k: Int = RRF_K,
    ): List<String> {
        val score = HashMap<String, Double>()
        val firstSeen = HashMap<String, Int>()
        var seen = 0
        for (list in listOf(lexical, vector)) {
            list.forEachIndexed { rank, name ->
                score[name] = (score[name] ?: 0.0) + 1.0 / (k + rank + 1)
                firstSeen.putIfAbsent(name, seen++)
            }
        }
        return score.keys.sortedWith(
            compareByDescending<String> { score[it] }
                .thenBy { firstSeen[it] }
                .thenBy { it },
        )
    }

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

    /**
     * The text a tool is embedded as.
     *
     * Underscores become spaces on purpose: `take_screenshot` is a single unknown token to a text
     * tokenizer, while `take screenshot` is two words the model has seen a great deal of. The name
     * leads because the name is what `tool_open` will be called with; the summary follows, already
     * capped by the caller.
     *
     * Whitespace is collapsed because the summary can be a `trimIndent()`-ed block: a newline in
     * the middle of the embedded text changes the tokenisation for no benefit, and it makes the
     * value untestable by eye.
     */
    fun embedText(name: String, summary: String): String =
        (name.replace('_', ' ') + " " + summary).trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.NEGATIVE_INFINITY
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
