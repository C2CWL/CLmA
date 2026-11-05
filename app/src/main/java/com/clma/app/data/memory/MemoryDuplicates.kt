package com.clma.app.data.memory

import com.clma.app.data.model.AssistantMemory

/**
 * 与已有记忆近似到此分数即视为重复，由 `memory_tool` 要求先合并而不是再插一条。
 *
 * 用字符二元组的 Jaccard 相似度：中英文都能用，也不依赖分词。阈值偏保守（宁可多问模型一次，
 * 也不要悄悄把两条不同的记忆合成一条）；命中时工具只是返回候选，真正的合并仍由模型决定。
 */
internal const val MEMORY_DUPLICATE_THRESHOLD = 0.6

/** 小写、去标点、折叠空白后的比较用文本。只用于比较，不回写记忆正文。 */
internal fun normalizeMemoryText(text: String): String =
    text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun bigrams(normalized: String): Set<String> {
    val compact = normalized.replace(" ", "")
    if (compact.isEmpty()) return emptySet()
    if (compact.length < 2) return setOf(compact)
    return (0 until compact.length - 1).map { compact.substring(it, it + 2) }.toSet()
}

/** 0（无关）到 1（归一化后完全相同）。空文本与任何内容都不相似。 */
internal fun memorySimilarity(a: String, b: String): Double {
    val left = normalizeMemoryText(a)
    val right = normalizeMemoryText(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    val leftGrams = bigrams(left)
    val rightGrams = bigrams(right)
    if (leftGrams.isEmpty() || rightGrams.isEmpty()) return 0.0
    val shared = leftGrams.count { it in rightGrams }
    return shared.toDouble() / (leftGrams.size + rightGrams.size - shared)
}

internal data class MemoryDuplicate(
    val memory: AssistantMemory,
    val similarity: Double,
)

/**
 * 与 [content] 最相似的已有记忆；没有达到 [threshold] 的返回 null。并列时取 id 更小的那条，
 * 保证同一份记忆库每次给出同样的答案。
 */
internal fun findMemoryDuplicate(
    content: String,
    existing: List<AssistantMemory>,
    threshold: Double = MEMORY_DUPLICATE_THRESHOLD,
): MemoryDuplicate? {
    var best: MemoryDuplicate? = null
    existing.forEach { memory ->
        val similarity = memorySimilarity(content, memory.content)
        if (similarity < threshold) return@forEach
        val current = best
        if (current == null || similarity > current.similarity ||
            (similarity == current.similarity && memory.id < current.memory.id)
        ) {
            best = MemoryDuplicate(memory, similarity)
        }
    }
    return best
}
