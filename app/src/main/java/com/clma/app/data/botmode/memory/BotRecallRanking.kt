// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/recall.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.memory

import java.util.Locale

private val words = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]+|[\p{L}\p{N}&&[^\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]]+""")
private val cjk = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]""")
private val stopWords = setOf(
    "the", "that", "this", "with", "from", "about", "what", "when", "where", "which",
    "were", "have", "will", "would", "could", "should", "please", "tell", "some", "your",
    "remember", "earlier", "previous", "你能", "可以", "请问", "什么", "怎么", "之前", "我们",
    "一下", "关于", "是否", "知道", "记得",
)

internal fun botRecallTerms(question: String): List<String> = recallTokens(question.trim().take(500))
    .map { it.first }
    .distinct()
    .take(12)
    .toList()

private fun recallTokens(text: String): Sequence<Pair<String, Int>> = words.findAll(text).flatMap { match ->
    val word = match.value.lowercase(Locale.ROOT)
    if (cjk.containsMatchIn(match.value)) {
        match.value.windowed(2).asSequence().mapIndexed { index, term -> term to (match.range.first + index) }
    } else {
        sequenceOf(word to match.range.first).filter { word.length >= 3 }
    }
}.filter { (term, _) -> term !in stopWords }

internal data class RecallMatch(val score: Int, val excerpt: String)

internal fun botRecallMatch(text: String, terms: Set<String>): RecallMatch? {
    val matches = recallTokens(text).filter { it.first in terms }.distinctBy { it.first }.toList()
    if (matches.isEmpty() || (terms.size >= 5 && matches.size < 2)) return null
    // Center the bounded excerpt near the cluster with the most distinct matching terms.
    val best = matches.maxBy { (_, offset) -> matches.count { (_, other) -> other in offset - 100..offset + 450 } }
    val start = (best.second - 100).coerceAtLeast(0)
    val end = (start + 600).coerceAtMost(text.length)
    val excerpt = (if (start > 0) "…" else "") + text.substring(start, end).trim() +
        (if (end < text.length) "…" else "")
    return RecallMatch(matches.size, excerpt)
}
