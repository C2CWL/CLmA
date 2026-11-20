package com.clma.app.data.ai

// Adapted from moxw (AGPL-3.0, 81bbb7e).

/**
 * Small, dependency-free event log for one agent turn.
 *
 * The log is intentionally separate from chat messages: it is runtime state, not user-visible
 * content. Events are append-only and snapshots are immutable, which makes the same record usable
 * by UI progress, recovery, analytics, and tests without re-parsing model messages.
 */
internal class TurnEventLog(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val events = ArrayList<TurnEvent>()
    private var nextSequence = 0L

    fun append(event: TurnEvent) = synchronized(lock) {
        val recorded = event.withMetadata(sequence = nextSequence++, timestampMs = clockMs())
        events += recorded
    }

    fun snapshot(): TurnEventSnapshot = synchronized(lock) { snapshotLocked() }

    fun clear(): TurnEventSnapshot = synchronized(lock) {
        events.clear()
        nextSequence = 0L
        snapshotLocked()
    }

    /** Returns a compact, model-independent summary suitable for diagnostics or checkpointing. */
    fun summary(): TurnSummary = synchronized(lock) {
        val current = events.toList()
        val toolCalls = current.filterIsInstance<TurnEvent.ToolCalled>()
        val toolResults = current.filterIsInstance<TurnEvent.ToolCompleted>()
        val completedIds = toolResults.mapTo(HashSet()) { it.callId }
        val calledIds = toolCalls.mapTo(LinkedHashSet()) { it.callId }
        TurnSummary(
            eventCount = current.size,
            lastSequence = current.lastOrNull()?.sequence,
            toolCallCount = calledIds.size,
            completedToolCallCount = completedIds.intersect(calledIds).size,
            pendingToolCallIds = calledIds.filterNot(completedIds::contains),
            failureCount = current.count { it is TurnEvent.Failed || (it is TurnEvent.ToolCompleted && !it.ok) },
            recoveryCount = current.count { it is TurnEvent.Recovered },
            compactionCount = current.count { it is TurnEvent.CompactionFinished },
        )
    }

    private fun snapshotLocked() = TurnEventSnapshot(events.toList())
}

internal data class TurnEventSnapshot(val events: List<TurnEvent>) {
    init { require(events.zipWithNext().all { it.first.sequence < it.second.sequence }) }
}

internal data class TurnSummary(
    val eventCount: Int,
    val lastSequence: Long?,
    val toolCallCount: Int,
    val completedToolCallCount: Int,
    val pendingToolCallIds: List<String>,
    val failureCount: Int,
    val recoveryCount: Int,
    val compactionCount: Int,
)

internal sealed interface TurnEvent {
    val sequence: Long
    val timestampMs: Long
    fun withMetadata(sequence: Long, timestampMs: Long): TurnEvent

    data class Started(
        val turnId: String,
        override val sequence: Long = UNASSIGNED_SEQUENCE,
        override val timestampMs: Long = UNASSIGNED_TIMESTAMP,
    ) : TurnEvent {
        override fun withMetadata(sequence: Long, timestampMs: Long) = copy(sequence = sequence, timestampMs = timestampMs)
    }

    data class ModelStep(
        val step: Int,
        val model: String? = null,
        override val sequence: Long = UNASSIGNED_SEQUENCE,
        override val timestampMs: Long = UNASSIGNED_TIMESTAMP,
    ) : TurnEvent {
        override fun withMetadata(sequence: Long, timestampMs: Long) = copy(sequence = sequence, timestampMs = timestampMs)
    }

    data class ToolCalled(
        val callId: String,
        val name: String,
        val argsDigest: String? = null,
        override val sequence: Long = UNASSIGNED_SEQUENCE,
        override val timestampMs: Long = UNASSIGNED_TIMESTAMP,
    ) : TurnEvent {
        override fun withMetadata(sequence: Long, timestampMs: Long) = copy(sequence = sequence, timestampMs = timestampMs)
    }

    data class ToolCompleted(
        val callId: String,
        val name: String,
        val ok: Boolean,
        val outputTokens: Int? = null,
        val truncated: Boolean = false,
        val durationMs: Long? = null,
        override val sequence: Long = UNASSIGNED_SEQUENCE,
        override val timestampMs: Long = UNASSIGNED_TIMESTAMP,
    ) : TurnEvent {
        override fun withMetadata(sequence: Long, timestampMs: Long) = copy(sequence = sequence, timestampMs = timestampMs)
    }

    data class CompactionStarted(
        val reason: String,
        val sourceEventCount: Int,
        override val sequence: Long = UNASSIGNED_SEQUENCE,
        override val timestampMs: Long = UNASSIGNED_TIMESTAMP,
    ) : TurnEvent {
        override fun withMetadata(sequence: Long, timestampMs: Long) = copy(sequence = sequence, timestampMs = timestampMs)
    }

    data class CompactionFinished(
        val retainedEventCount: Int,
        val summaryTokens: Int? = null,
        override val sequence: Long = UNASSIGNED_SEQUENCE,
        override val timestampMs: Long = UNASSIGNED_TIMESTAMP,
    ) : TurnEvent {
        override fun withMetadata(sequence: Long, timestampMs: Long) = copy(sequence = sequence, timestampMs = timestampMs)
    }

    data class Recovered(
        val fromSequence: Long?,
        val reason: String,
        override val sequence: Long = UNASSIGNED_SEQUENCE,
        override val timestampMs: Long = UNASSIGNED_TIMESTAMP,
    ) : TurnEvent {
        override fun withMetadata(sequence: Long, timestampMs: Long) = copy(sequence = sequence, timestampMs = timestampMs)
    }

    data class Failed(
        val stage: String,
        val errorType: String,
        val retryable: Boolean,
        override val sequence: Long = UNASSIGNED_SEQUENCE,
        override val timestampMs: Long = UNASSIGNED_TIMESTAMP,
    ) : TurnEvent {
        override fun withMetadata(sequence: Long, timestampMs: Long) = copy(sequence = sequence, timestampMs = timestampMs)
    }

    data class Finished(
        val success: Boolean,
        override val sequence: Long = UNASSIGNED_SEQUENCE,
        override val timestampMs: Long = UNASSIGNED_TIMESTAMP,
    ) : TurnEvent {
        override fun withMetadata(sequence: Long, timestampMs: Long) = copy(sequence = sequence, timestampMs = timestampMs)
    }
}

private const val UNASSIGNED_SEQUENCE = -1L
private const val UNASSIGNED_TIMESTAMP = -1L
