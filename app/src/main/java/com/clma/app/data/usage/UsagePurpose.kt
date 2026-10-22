package me.rerere.rikkahub.data.usage

/**
 * Why a model call happened.
 *
 * The ledger stores the enum *name*, so adding values later never invalidates existing rows.
 * The buckets are deliberately coarse: one per caller class rather than one per call site, so
 * that the numbers stay comparable across the autonomous paths.
 */
enum class UsagePurpose {
    /** The user-facing chat turn itself (first model call of a turn). */
    MAIN,

    /** Follow-up model calls inside the same turn, after a tool result came back. */
    TOOL_LOOP,

    /** Model-initiated context compaction summarisation. */
    COMPACTION,

    /** Conversation title generation. */
    TITLE,

    /** Follow-up suggestion chips. */
    SUGGESTION,

    /** Cold-memory / knowledge-base extraction. */
    MEMORY_EXTRACT,

    /** A sub-agent run. */
    SUBAGENT,

    /** A scheduled job run. */
    CRON,

    /** A workflow step run. */
    WORKFLOW,

    /** The skill tester running a skill in isolation. */
    SKILL_TEST,

    /** Message translation. */
    TRANSLATION,

    /** Not classified yet. Recorded anyway, so that the totals still add up. */
    UNKNOWN,
}
