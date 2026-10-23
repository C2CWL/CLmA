package com.clma.app.data.usage

import com.clma.ai.core.MessageRole

/**
 * P2-12b — the per-turn reading of the usage ledger.
 *
 * The per-message footer used to show `message.usage` only. That figure is produced by
 * `StreamChunkHandler` folding every call of a turn together with `TokenUsage.merge`, and that
 * merge overwrites each figure with the incoming non-zero value — so a turn that issued three
 * model calls displayed the numbers of its **last** call, not the sum (P2-11 defect A3). The
 * ledger records one row per round trip, so summing the rows that fall inside the turn's time
 * window is the first place a multi-call turn can actually be read.
 *
 * Attribution is by time window, exactly as agreed for P2-12:
 *
 *  - a node owns `[currentMessage.createdAt, nextNode.currentMessage.createdAt)`;
 *  - the last node's window is open-ended.
 *
 * The window deliberately ends at the *next* node rather than at `message.finishedAt`: a
 * streamed call writes its ledger row only once the flow completes, which is a few
 * milliseconds **after** the `Finish` chunk stamped `finishedAt` (P2-12a). A window closed at
 * `finishedAt` would therefore miss every streamed call — i.e. the whole default chat path.
 *
 * Two purposes are excluded by name — [UsagePurpose.TITLE] and [UsagePurpose.SUGGESTION].
 * Both fire right after a turn, reuse its conversation id and land in the same window, but
 * neither is part of the reply; without the filter every turn would be credited with one extra
 * title/suggestion call. Every other purpose is kept and labelled by the UI.
 *
 * Pure by construction: no Room runtime, no Android, no coroutines. [UsageRecordEntity] is a
 * plain data class — only its annotations need androidx.room on the compile classpath.
 */

/** Half-open time window `[startMs, endExclusiveMs)` that a turn's rows fall into. */
data class UsageTurnWindow(
    val startMs: Long,
    val endExclusiveMs: Long,
) {
    fun contains(atMs: Long): Boolean = atMs >= startMs && atMs < endExclusiveMs
}

/** One model round trip inside a turn, ready for the expandable per-call list. */
data class UsageCallView(
    val purpose: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int,
    val cachedTokensReported: Boolean,
    val providerCostUsd: Double?,
    val costMicros: Long?,
    val latencyMs: Long?,
    val streaming: Boolean,
    val atMs: Long,
)

/**
 * The summed view of one turn.
 *
 * [cacheHitTokens] / [cachePromptTokens] are summed only over the rows that actually reported
 * cache fields, so a provider that does not report them cannot drag the rate toward zero;
 * [cacheReported] says whether any row did (P2-11a's "unknown != 0").
 */
data class TurnUsageView(
    val calls: List<UsageCallView>,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheHitTokens: Long,
    val cachePromptTokens: Long,
    val cacheReported: Boolean,
    val providerCostUsd: Double?,
    val costMicros: Long?,
) {
    val callCount: Int get() = calls.size

    /**
     * D6 - output tokens written by the calls that reported a latency. The rate below is built
     * from this subset, so a call nobody measured cannot dilute it.
     */
    val measuredOutputTokens: Long get() = calls.filter { it.latencyMs != null }.sumOf { it.outputTokens.toLong() }

    /**
     * D6 - the turn's total **generation** time: the sum of the per-call latencies. A ledger row
     * is one model round trip and a tool runs *between* round trips, so this excludes tool
     * execution by construction - exactly the denominator "tok/s" should use.
     */
    val generationMs: Long get() = calls.mapNotNull { it.latencyMs }.sum()

    /** D6 - output throughput over [generationMs], or null when no call reported a latency. */
    val tokensPerSecond: Double? get() =
        generationMs.takeIf { it > 0 }?.let { measuredOutputTokens * 1000.0 / it }

    /**
     * True when the only price available was recomputed from the price table. The UI marks
     * those with a `~`; a provider-reported cost is shown as-is.
     */
    val estimatedCostOnly: Boolean get() = providerCostUsd == null && costMicros != null
}

/**
 * D6 — one conversation node, reduced to what the footer needs: where it sits in time, its role
 * (a turn's wall clock starts at the last **user** message before it) and when its reply ended.
 */
data class TurnNode(
    val messageId: String,
    val role: MessageRole,
    val createdAtMs: Long,
    val finishedAtMs: Long?,
)

/**
 * D6 — everything the message footer renders for one turn: the aggregated ledger rows (null until
 * the first row of the turn lands) plus the wall-clock window the turn spans.
 *
 * [running] is passed in rather than derived from [finishedAtMs] so the view stays pure, and so a
 * turn that is still streaming counts up to “now” instead of freezing on a stale `finishedAt`.
 * [finishedAtMs] is null whenever the end is not known — a running turn, or a stopped/failed one.
 */
data class TurnFooter(
    val usage: TurnUsageView?,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val running: Boolean,
)

object UsageTurnViewFactory {
    /**
     * Auxiliary calls that fire right after a turn and reuse its conversation id, so a purely
     * time-based window would fold them into the reply. Excluded by purpose instead.
     */
    private val EXCLUDED_PURPOSES: Set<String> = setOf(
        UsagePurpose.TITLE.name,
        UsagePurpose.SUGGESTION.name,
    )

    fun isTurnCall(purpose: String): Boolean = purpose !in EXCLUDED_PURPOSES

    /**
     * Turns an ordered list of `(messageId, startMs)` into half-open windows, each ending where
     * the next begins. A node whose start is not strictly after the previous one gets an empty
     * window rather than a reversed one, so a duplicated timestamp can never absorb a
     * neighbour's rows.
     */
    fun windowsFor(nodes: List<Pair<String, Long>>): List<Pair<String, UsageTurnWindow>> =
        nodes.mapIndexed { index, node ->
            val startMs = node.second
            val nextStart = nodes.getOrNull(index + 1)?.second
            val endExclusive = when {
                nextStart == null -> Long.MAX_VALUE
                nextStart > startMs -> nextStart
                else -> startMs
            }
            node.first to UsageTurnWindow(startMs, endExclusive)
        }

    /** The turn's rows, or null when the window holds none (the UI then falls back). */
    fun turnUsageFor(
        records: List<UsageRecordEntity>,
        window: UsageTurnWindow,
    ): TurnUsageView? {
        val rows = records.filter { isTurnCall(it.purpose) && window.contains(it.createdAtMs) }
        return if (rows.isEmpty()) null else aggregate(rows)
    }

    /** Pure sum of one turn's rows, oldest call first. */
    fun aggregate(rows: List<UsageRecordEntity>): TurnUsageView {
        val ordered = rows.sortedWith(compareBy({ it.createdAtMs }, { it.id }))
        val calls = ordered.map { row ->
            UsageCallView(
                purpose = row.purpose,
                inputTokens = row.inputTokens,
                outputTokens = row.outputTokens,
                cachedTokens = row.cachedTokens,
                cachedTokensReported = row.cachedTokensReported,
                providerCostUsd = row.providerCostUsd,
                costMicros = row.costMicros,
                latencyMs = row.latencyMs,
                streaming = row.streaming,
                atMs = row.createdAtMs,
            )
        }

        var inputTokens = 0L
        var outputTokens = 0L
        var cacheHit = 0L
        var cachePrompt = 0L
        var cacheReported = false
        var providerCost: Double? = null
        var costMicros: Long? = null
        for (row in ordered) {
            inputTokens += row.inputTokens
            outputTokens += row.outputTokens
            if (row.cachedTokensReported) {
                cacheReported = true
                cacheHit += row.cachedTokens
                cachePrompt += row.inputTokens
            }
            // Missing stays missing: a null price is never summed as zero.
            row.providerCostUsd?.let { providerCost = (providerCost ?: 0.0) + it }
            row.costMicros?.let { costMicros = (costMicros ?: 0L) + it }
        }

        return TurnUsageView(
            calls = calls,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheHitTokens = cacheHit,
            cachePromptTokens = cachePrompt,
            cacheReported = cacheReported,
            providerCostUsd = providerCost,
            costMicros = costMicros,
        )
    }

    /** Message id -> turn view, for every window that actually holds rows. */
    fun map(
        windows: List<Pair<String, UsageTurnWindow>>,
        records: List<UsageRecordEntity>,
    ): Map<String, TurnUsageView> {
        val result = LinkedHashMap<String, TurnUsageView>()
        for ((messageId, window) in windows) {
            turnUsageFor(records, window)?.let { result[messageId] = it }
        }
        return result
    }

    /**
     * D6 — the per-message footers, one per **turn**.
     *
     * A turn is everything between two user messages, not one assistant node. A single reply can
     * be split into several assistant nodes while its tool loop runs (the UI folds them into one
     * card), and each node used to claim only the rows of its own `[createdAt, next.createdAt)`
     * slice: the folded nodes showed no footer at all, while the chat top bar — which sums every
     * footer — still counted their rows. The visible footers then added up to a fraction of the
     * header, which read as a tenfold over-count.
     *
     * So the rows are now attributed by the turn's window instead:
     *
     *  - a turn starts at its opening user message and ends at the next user message;
     *  - every assistant node in it contributes its rows to one [TurnFooter], attached to the
     *    turn's **last** assistant node — the one the UI renders as the reply;
     *  - an interjection (a user message between an un-finalized assistant half and its
     *    continuation) does not start a new turn: the UI merges those three nodes into one
     *    continuous reply, so splitting there would drop the first half's usage.
     *
     * The footer's wall clock still ends at that last node's `finishedAt`, so idle time between
     * two turns is never billed to the one that just ended. While [liveMessageId] is that node
     * the end is unknown, so [TurnFooter.finishedAtMs] is null and [TurnFooter.running] is true.
     */
    fun footersFor(
        nodes: List<TurnNode>,
        records: List<UsageRecordEntity>,
        liveMessageId: String? = null,
    ): Map<String, TurnFooter> {
        val turns = turnsOf(nodes)
        val result = LinkedHashMap<String, TurnFooter>()
        turns.forEachIndexed { index, turn ->
            val assistant = turn.lastAssistant ?: return@forEachIndexed
            val startMs = turn.startMs
            val nextStartMs = turns.getOrNull(index + 1)?.startMs
            val window = UsageTurnWindow(
                startMs = startMs,
                // A non-increasing neighbour start yields an empty window rather than a reversed
                // one, so a duplicated timestamp can never absorb the next turn's rows.
                endExclusiveMs = when {
                    nextStartMs == null -> Long.MAX_VALUE
                    nextStartMs > startMs -> nextStartMs
                    else -> startMs
                },
            )
            val usage = turnUsageFor(records, window)
            val running = assistant.messageId == liveMessageId
            if (usage == null && !running) return@forEachIndexed
            result[assistant.messageId] = TurnFooter(
                usage = usage,
                startedAtMs = startMs,
                finishedAtMs = if (running) null else assistant.finishedAtMs,
                running = running,
            )
        }
        return result
    }

    /** One turn's span: the user message that opened it and its last assistant node. */
    private data class TurnSpan(
        val startMs: Long,
        var lastAssistant: TurnNode? = null,
    )

    /**
     * Groups the ordered nodes into turns. A user message opens a turn unless it is an
     * interjection — an un-finalized assistant half before it and a continuation after it.
     */
    private fun turnsOf(nodes: List<TurnNode>): List<TurnSpan> {
        val turns = mutableListOf<TurnSpan>()
        var current: TurnSpan? = null
        nodes.forEachIndexed { index, node ->
            if (node.role == MessageRole.USER) {
                val prev = nodes.getOrNull(index - 1)
                val next = nodes.getOrNull(index + 1)
                val interjection = prev?.role == MessageRole.ASSISTANT &&
                    prev.finishedAtMs == null &&
                    next?.role == MessageRole.ASSISTANT
                if (interjection) return@forEachIndexed
                current?.let { turns += it }
                current = TurnSpan(startMs = node.createdAtMs)
                return@forEachIndexed
            }
            val turn = current ?: TurnSpan(startMs = nodes.first().createdAtMs).also { current = it }
            turn.lastAssistant = node
        }
        current?.let { turns += it }
        return turns
    }

    /** 合并两段 turn usage（插话打断时前半结转到后半）。null 视为空。 */
    fun merge(a: TurnUsageView?, b: TurnUsageView?): TurnUsageView? {
        if (a == null) return b
        if (b == null) return a
        return TurnUsageView(
            calls = a.calls + b.calls,
            inputTokens = a.inputTokens + b.inputTokens,
            outputTokens = a.outputTokens + b.outputTokens,
            cacheHitTokens = a.cacheHitTokens + b.cacheHitTokens,
            cachePromptTokens = a.cachePromptTokens + b.cachePromptTokens,
            cacheReported = a.cacheReported || b.cacheReported,
            providerCostUsd = sumNullable(a.providerCostUsd, b.providerCostUsd),
            costMicros = sumNullable(a.costMicros, b.costMicros),
        )
    }

    private fun sumNullable(a: Double?, b: Double?): Double? =
        if (a == null) b else if (b == null) a else a + b

    private fun sumNullable(a: Long?, b: Long?): Long? =
        if (a == null) b else if (b == null) a else a + b
}
