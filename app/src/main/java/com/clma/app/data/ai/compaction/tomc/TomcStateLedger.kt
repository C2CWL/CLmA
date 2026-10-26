package com.clma.app.data.ai.compaction.tomc

/** One conversation turn, already flattened to plain text. */
internal data class TomcTurn(val index: Int, val role: String, val text: String)

/**
 * What the compression is being asked to keep answerable.
 *
 * The route is picked from the user's own words by [TomcRouteDetector] — no model call, no
 * classifier weights, just cue tables. It changes one thing only: how the character budget is
 * split between the structured ledger and the raw excerpts. A "what is the current X" question
 * wants the ledger; "what did we say about X" wants the text back.
 */
internal enum class TomcRoute { STATE, RELATION, COUNT, RAW }

internal object TomcRouteDetector {

    private val COUNT_CUES = listOf(
        "多少", "几个", "几次", "几条", "几项", "数量", "几次了", "how many", "how often", "count",
    )
    private val RELATION_CUES = listOf(
        "关系", "依赖", "引用了", "谁调用", "被谁", "关联", "上下游", "调用了",
        "relat", "depend", "referenc", "who calls", "caller",
    )
    private val STATE_CUES = listOf(
        "是什么", "是啥", "现在", "当前", "状态", "版本", "配置", "参数", "决定", "确认",
        "是不是", "有没有", "记得", "what is", "what's", "current", "status", "version",
        "config", "decided", "confirmed", "remember",
    )

    fun detect(query: String): TomcRoute {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return TomcRoute.RAW
        if (COUNT_CUES.any { q.contains(it) }) return TomcRoute.COUNT
        if (RELATION_CUES.any { q.contains(it) }) return TomcRoute.RELATION
        if (STATE_CUES.any { q.contains(it) }) return TomcRoute.STATE
        return TomcRoute.RAW
    }
}

/**
 * One entry of the state ledger. [weight] is 3 for an explicit assignment (`key = value`), 2 for
 * a decision-shaped sentence, 1 for a file or version mention the conversation happened to carry.
 */
internal data class TomcFact(
    val key: String,
    val value: String,
    val sourceIndex: Int,
    val weight: Int = 1,
)

/**
 * The 42% of a TOMC compression that is *state* rather than *text*.
 *
 * The rule that makes it a ledger instead of a quote pile: **a later assignment replaces an
 * earlier one for the same key**. A conversation that says `version = 0.6.9` and then
 * `version = 0.7.0` has exactly one current version, and a summary that reports both has made
 * the model do the reconciliation itself — which is precisely the failure mode ("it forgot
 * what we settled on") this is meant to remove.
 *
 * Extraction is deliberately boring: assignments, decision cues, file paths, version numbers.
 * No inference, no entailment, nothing that could invent a fact that was never said.
 */
internal object TomcStateLedger {

    private const val MAX_KEY = 24
    private const val MAX_VALUE = 160
    private const val MAX_FILES = 8
    private const val MAX_VERSIONS = 4

    /** `- target_sdk = 34`, `版本：0.7.0`, `key: value` — one short key, then a value. */
    private val ASSIGNMENT = Regex("""^\s*(?:[-*•]\s*)?([^=:：]{1,24}?)\s*[=:：]\s*(\S.*)$""")

    private val DECISION_CUES = listOf(
        "决定", "约定", "规则", "要求", "默认", "禁止", "不要", "必须", "统一", "保持",
        "decided", "agreed", "rule", "must ", "never ", "default ", "requirement",
    )

    private val FILE_REFERENCE =
        Regex("""[\w./-]+\.(?:kt|kts|java|py|md|xml|json|gradle|yaml|yml|toml|txt)\b""")

    private val VERSION_REFERENCE = Regex("""\bv?\d+\.\d+(?:\.\d+)?\b""")

    fun extract(turns: List<TomcTurn>): List<TomcFact> {
        val byKey = LinkedHashMap<String, TomcFact>()
        var decisionSeq = 0

        for (turn in turns) {
            for (line in turn.text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.length < 3) continue

                val assignment = ASSIGNMENT.matchEntire(trimmed)
                if (assignment != null) {
                    val rawKey = assignment.groupValues[1].trim().trim('-', '•', '*').trim()
                    val value = assignment.groupValues[2].trim().take(MAX_VALUE)
                    // A "key" with spaces is a sentence with a colon in it, not an assignment.
                    if (rawKey.isNotEmpty() && rawKey.length <= MAX_KEY &&
                        !rawKey.contains(' ') && value.isNotEmpty()
                    ) {
                        byKey[rawKey.lowercase()] = TomcFact(rawKey, value, turn.index, weight = 3)
                        continue
                    }
                }

                val lower = trimmed.lowercase()
                if (DECISION_CUES.any { lower.contains(it) }) {
                    decisionSeq += 1
                    byKey["决定#$decisionSeq"] = TomcFact(
                        key = "决定",
                        value = trimmed.removePrefix("-").trim().take(MAX_VALUE),
                        sourceIndex = turn.index,
                        weight = 2,
                    )
                }
            }
        }

        val files = LinkedHashSet<String>()
        val versions = LinkedHashSet<String>()
        for (turn in turns) {
            for (match in FILE_REFERENCE.findAll(turn.text)) {
                if (files.size < MAX_FILES) files += match.value
            }
            for (match in VERSION_REFERENCE.findAll(turn.text)) {
                if (versions.size < MAX_VERSIONS) versions += match.value
            }
        }
        val lastIndex = turns.lastOrNull()?.index ?: 0
        files.forEachIndexed { i, path ->
            byKey["file#$i"] = TomcFact("涉及文件", path, lastIndex, weight = 1)
        }
        versions.forEachIndexed { i, version ->
            byKey["version#$i"] = TomcFact("出现版本", version, lastIndex, weight = 1)
        }

        return byKey.values.sortedBy { it.sourceIndex }
    }

    /**
     * Renders at most [budgetChars] characters. When the ledger does not fit, the OLDEST facts are
     * the ones dropped: for state, recency is authority, so keeping `version = 0.7.0` and losing
     * `version = 0.6.9` is not a loss at all — it is the compression working.
     */
    fun render(facts: List<TomcFact>, budgetChars: Int): String {
        if (facts.isEmpty() || budgetChars <= 0) return ""
        val chosen = LinkedHashSet<TomcFact>()
        var used = 0
        for (fact in facts.sortedWith(compareByDescending<TomcFact> { it.weight }.thenByDescending { it.sourceIndex })) {
            val line = "- ${fact.key}: ${fact.value}"
            val cost = line.length + 1
            if (used + cost > budgetChars) continue
            chosen += fact
            used += cost
        }
        if (chosen.isEmpty()) return ""
        return chosen.sortedBy { it.sourceIndex }.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }
}
