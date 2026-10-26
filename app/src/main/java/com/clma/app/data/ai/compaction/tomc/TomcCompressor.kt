package com.clma.app.data.ai.compaction.tomc

/**
 * TOMC — Task-Oriented Memory Compression, local track.
 *
 * The compression that upstream does with an LLM turn (map/reduce over the history) done here
 * with arithmetic: a state ledger plus BM25-ranked raw excerpts, both sized to a character
 * budget the caller passes in. Zero network calls, zero tokens billed, no model swap, and the
 * output is deterministic — the same history always compresses to the same bytes, which keeps
 * the provider's prompt cache warm across turns.
 *
 * Two halves, and the split is the whole design:
 *
 *  - **[TomcStateLedger]** answers "what is true right now". Assignments, decisions, files,
 *    versions; later facts supersede earlier ones. This is the part an LLM summary usually
 *    gets wrong, because a summary of a long chat is a summary of history, not of state.
 *  - **BM25 excerpts** answer "what was actually said". Ranked against the live query plus the
 *    newest turn, so the round that used a word is the round that survives.
 *
 * The share between them follows [TomcRouteDetector]: a status question leans on the ledger, a
 * "what did we say" question leans on the text. That is the whole "adaptive" claim — no
 * learned weights, no hidden state, just a cue table and a ratio.
 *
 * What this is NOT: a replacement for the LLM compactor. It cannot paraphrase, cannot merge two
 * facts into one sentence, and cannot reason about what a later turn implied. It is the cheap
 * track for "the budget just got tight and the thread is long", and the LLM track stays for the
 * turns where meaning beats structure.
 */
internal object TomcCompressor {

    /** Default ledger share of the budget, used by the COUNT / RELATION routes. */
    const val LEDGER_SHARE = 0.42

    /** Below this many characters a segment is a fragment, not a quotation — skip it. */
    const val MIN_SEGMENT_CHARS = 24

    private const val LEDGER_HEADER = "## 状态账本\n"
    private const val EXCERPT_HEADER = "## 相关片段\n"
    private const val RECENT_QUERY_CHARS = 400

    /** How the budget is split. Kept as a value so a test can pin the arithmetic. */
    data class Plan(val ledgerChars: Int, val excerptChars: Int) {
        val total: Int get() = ledgerChars + excerptChars
    }

    data class Result(
        val text: String,
        val route: TomcRoute,
        val plan: Plan,
        val factCount: Int,
        val excerptTurns: List<Int>,
        val droppedTurns: Int,
    ) {
        val usedChars: Int get() = text.length
    }

    fun ledgerShare(route: TomcRoute): Double = when (route) {
        // "What is the current X" — structure wins.
        TomcRoute.STATE -> 0.50
        // "What did we say about X" — give the text its room back.
        TomcRoute.RAW -> 0.25
        TomcRoute.COUNT, TomcRoute.RELATION -> LEDGER_SHARE
    }

    fun plan(budgetChars: Int, route: TomcRoute): Plan {
        val budget = budgetChars.coerceAtLeast(0)
        val ledger = (budget * ledgerShare(route)).toInt()
        return Plan(ledgerChars = ledger, excerptChars = budget - ledger)
    }

    fun compress(turns: List<TomcTurn>, query: String, budgetChars: Int): Result {
        val route = TomcRouteDetector.detect(query)
        val plan = plan(budgetChars, route)
        if (turns.isEmpty() || budgetChars <= 0) {
            return Result("", route, plan, factCount = 0, excerptTurns = emptyList(), droppedTurns = turns.size)
        }

        val facts = TomcStateLedger.extract(turns)
        val ledgerBody = TomcStateLedger.render(facts, plan.ledgerChars)
        val ledgerBlock =
            if (ledgerBody.isEmpty()) "" else LEDGER_HEADER + ledgerBody + "\n"

        // The ledger gets first claim; whatever it did not use is handed to the excerpts so a
        // history with no structured facts does not waste 42% of the budget on a header.
        val excerptBudget = budgetChars - ledgerBlock.length - EXCERPT_HEADER.length
        val excerpts = selectExcerpts(turns, query, excerptBudget)

        val sb = StringBuilder(ledgerBlock)
        if (excerpts.isNotEmpty()) {
            sb.append(EXCERPT_HEADER)
            for ((turn, body) in excerpts) {
                sb.append('[').append(turn.role).append(" #").append(turn.index).append("] ")
                sb.append(body)
                if (body.length < turn.text.length) sb.append(" …")
                sb.append('\n')
            }
        }

        return Result(
            text = sb.toString(),
            route = route,
            plan = plan,
            factCount = facts.size,
            excerptTurns = excerpts.map { it.first.index },
            droppedTurns = turns.size - excerpts.size,
        )
    }

    /**
     * Picks the excerpt turns that fit [budgetChars], best-first, then returns them in
     * chronological order (a summary that jumps backwards in time reads like a different
     * conversation). The newest turn is always a candidate: it is the one the next request is
     * about, whether or not it shares a token with the query.
     */
    fun selectExcerpts(turns: List<TomcTurn>, query: String, budgetChars: Int): List<Pair<TomcTurn, String>> {
        if (turns.isEmpty() || budgetChars <= 0) return emptyList()
        val anchor = turns.last().text.take(RECENT_QUERY_CHARS)
        val queryTokens = TomcTokenizer.tokens(query) + TomcTokenizer.tokens(anchor)
        val index = TomcBm25(turns.map { TomcTokenizer.tokens(it.text) })

        val order = LinkedHashSet<Int>()
        order += turns.lastIndex
        order += index.rank(queryTokens)

        val picked = ArrayList<Pair<TomcTurn, String>>()
        var used = 0
        for (position in order) {
            val turn = turns.getOrNull(position) ?: continue
            // +1 newline and +3 for the " …" a truncated body carries, so the assembled
            // text can never overshoot the budget it was given.
            val header = "[${turn.role} #${turn.index}] ".length + 4
            val room = budgetChars - used - header
            if (room < MIN_SEGMENT_CHARS) continue
            val body = turn.text.take(room).trim()
            if (body.isEmpty()) continue
            picked += turn to body
            used += header + body.length + 1
        }
        return picked.sortedBy { it.first.index }
    }
}
