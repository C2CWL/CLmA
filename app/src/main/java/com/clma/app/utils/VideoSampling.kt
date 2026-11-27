package com.clma.app.utils

internal object VideoSampling {
    const val MAX_FRAMES = 12
    const val DETAIL_FRAMES = 6
    const val MAX_DETAIL_FRAMES = 8

    data class Sample(val timeUs: Long, val colors: IntArray)

    fun overviewCount(durationMs: Long): Int = when {
        durationMs <= 3_000 -> 3
        durationMs <= 15_000 -> 5
        durationMs <= 60_000 -> 8
        else -> MAX_FRAMES
    }

    fun detailRange(start: Double, end: Double): LongRange? {
        if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start ||
            end - start > 60 || end > Long.MAX_VALUE / 1_000_000.0
        ) return null
        val first = (start * 1_000_000).toLong()
        val last = (end * 1_000_000).toLong()
        return (first..last).takeIf { last > first }
    }

    fun times(range: LongRange, count: Int): List<Long> = when {
        range.isEmpty() || count <= 0 -> emptyList()
        count == 1 -> listOf(range.first)
        else -> List(count) { index ->
            range.first + ((range.last - range.first) * (index.toDouble() / (count - 1))).toLong()
        }.distinct()
    }

    private fun channelDifference(a: Int, b: Int, shift: Int): Int =
        kotlin.math.abs(((a shr shift) and 255) - ((b shr shift) and 255))

    /** Keep temporal coverage while giving transitions more weight than unchanged stretches. */
    fun select(samples: List<Sample>, limit: Int): List<Long> {
        if (samples.isEmpty() || limit <= 0) return emptyList()
        val changes = samples.zipWithNext { previous, next ->
            previous.colors.indices.sumOf { index ->
                val a = previous.colors[index]
                val b = next.colors[index]
                channelDifference(a, b, 0) + channelDifference(a, b, 8) + channelDifference(a, b, 16)
            }.toDouble() / (previous.colors.size * 3)
        }
        val count = minOf(limit, 1 + changes.count { it >= 5.0 }, samples.size)
        val selected = mutableSetOf(0)
        while (selected.size < count) {
            val next = samples.indices.filterNot { it in selected }.maxBy { index ->
                val distance = selected.minOf { kotlin.math.abs(it - index) }
                val change = maxOf(changes.getOrElse(index - 1) { 0.0 }, changes.getOrElse(index) { 0.0 })
                distance.toDouble() / samples.size + change / 40.0
            }
            selected += next
        }
        return selected.sorted().map { samples[it].timeUs }
    }
}
