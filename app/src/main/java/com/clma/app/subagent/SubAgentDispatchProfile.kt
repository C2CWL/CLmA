package com.clma.app.subagent

/**
 * The five-level sub-agent dispatch posture the device owner picks in Settings → Sub-agents.
 *
 * Replaces the two static number inputs (per-assistant cap + global cap) with a single semantic
 * selector so the user says "how aggressively should the model delegate" instead of tuning raw
 * concurrency numbers.
 *
 * ★拍板：升级默认 = [NEVER]（绝不）— the safest posture; the user opts *out* of delegation
 * deliberately rather than discovering it was on.
 *
 * - [NEVER]: dispatch is disabled. The `subagent_dispatch` tool is not registered; the engine
 *   refuses any dispatch with `disabled_by_policy`. The model is told (via the policy-injection
 *   layer) to do all work inline.
 * - [ASSIST]: conservative. Low caps, short slot wait, tight max_trips/timeout ceilings.
 *   Description tone discourages delegation ("only when your own context cannot fit the work").
 * - [NORMAL]: standard dispatch (global 30, per-assistant 8).
 * - [BURST]: high concurrency. Encourages parallel dispatch in the tool description.
 * - [UNLEASHED]: maximum. Caps at the engine's hard limits, loosest ceilings, aggressive tone.
 */
enum class SubAgentDispatchProfile {
    NEVER,
    ASSIST,
    NORMAL,
    BURST,
    UNLEASHED,
}

/**
 * The concrete limits a [SubAgentDispatchProfile] maps to. Pure data — no Android, no I/O.
 *
 * @property globalCap the all-assistants concurrency ceiling the engine enforces.
 * @property perAssistantCap the per-parent-assistant ceiling.
 * @property slotWaitMs how long an over-cap dispatch queues for a free slot before refusing.
 * @property maxTripsCeiling the upper bound the validator clamps `max_trips` to.
 * @property timeoutCeilingSec the upper bound the validator clamps `timeout_seconds` to.
 * @property descriptionTone which static description template the dispatch tool uses.
 */
data class DispatchCaps(
    val globalCap: Int,
    val perAssistantCap: Int,
    val slotWaitMs: Long,
    val maxTripsCeiling: Int,
    val timeoutCeilingSec: Int,
    val descriptionTone: DescriptionTone,
)

enum class DescriptionTone { NEVER, ASSIST, NORMAL, BURST, UNLEASHED }

/**
 * Pure mapping from a [SubAgentDispatchProfile] to its [DispatchCaps]. Unit-testable on a bare JVM.
 *
 * The [NORMAL] profile allows 8 concurrent runs per assistant, with a global cap of 30.
 */
object SubAgentDispatchProfileRules {

    /**
     * Process-scoped live profile, set by ChatService before tool construction (non-suspend
     * factory reads this). Mirrors [com.clma.app.data.ai.limits.ToolRuntimeLimits] pattern.
     */
    @Volatile private var liveProfile: SubAgentDispatchProfile = SubAgentDispatchProfile.NEVER

    fun setLiveDispatchProfile(p: SubAgentDispatchProfile) { liveProfile = p }
    fun getLiveDispatchProfile(): SubAgentDispatchProfile = liveProfile

    fun caps(profile: SubAgentDispatchProfile): DispatchCaps = when (profile) {
        SubAgentDispatchProfile.NEVER -> DispatchCaps(0, 0, 0, 0, 0, DescriptionTone.NEVER)
        SubAgentDispatchProfile.ASSIST -> DispatchCaps(4, 1, 10_000L, 8, 300, DescriptionTone.ASSIST)
        SubAgentDispatchProfile.NORMAL -> DispatchCaps(30, 8, 30_000L, 60, 1800, DescriptionTone.NORMAL)
        SubAgentDispatchProfile.BURST -> DispatchCaps(48, 8, 60_000L, 60, 1800, DescriptionTone.BURST)
        SubAgentDispatchProfile.UNLEASHED -> DispatchCaps(64, 16, 120_000L, 60, 3600, DescriptionTone.UNLEASHED)
    }

    /** Shared by the runtime policy and both dispatch schemas. */
    fun dispatchGuidance(profile: SubAgentDispatchProfile): String {
        val posture = when (caps(profile).descriptionTone) {
            DescriptionTone.NEVER -> return "Sub-agent dispatch: NEVER — disabled; do all work inline."
            DescriptionTone.ASSIST ->
                "ASSIST — work inline by default; delegate a focused lookup or review when it " +
                    "materially reduces context load or needs specialist work."
            DescriptionTone.NORMAL ->
                "NORMAL — proactively delegate useful independent research, lookup, or review " +
                    "subtasks; do not wait for the user to request delegation."
            DescriptionTone.BURST ->
                "BURST — prefer parallel delegation: split independent work into focused tasks " +
                    "and use mission_dispatch for a batch, or background subagent_dispatch " +
                    "while you do complementary work."
            DescriptionTone.UNLEASHED ->
                "UNLEASHED — delegation-first: for every substantive task, identify delegable " +
                    "parts and dispatch them before doing the remaining work inline. Delegate " +
                    "even one useful independent subtask; dispatch independent parts in parallel. " +
                    "Keep planning, integration, and final verification in the parent."
        }
        return "Sub-agent dispatch: $posture " +
            "Act on this selection, not merely describe a delegation plan. Trivial replies and " +
            "inseparable tasks may stay inline. Do not duplicate a child's work, create busywork, " +
            "or run concurrent edits to the same files. Respect tool availability, budgets, " +
            "approval policy, and safety constraints; delegation does not grant extra authority. " +
            "If useful delegation is blocked, state the concrete blocker and continue unblocked work."
    }

    /**
     * The effective per-assistant cap, combining the profile ceiling with the assistant's own
     * [com.clma.app.data.model.Assistant.maxConcurrentSubAgents] field as a further tighten.
     * `min(profile cap, assistant field)` so the assistant can never exceed the profile, but can
     * be more restrictive. Returns 0 when the profile disables dispatch.
     */
    fun effectivePerAssistantCap(
        profile: SubAgentDispatchProfile,
        assistantCap: Int,
    ): Int {
        val profileCap = caps(profile).perAssistantCap
        if (profileCap == 0) return 0
        return minOf(profileCap, assistantCap)
    }

    /** True when the profile allows dispatch at all. */
    fun isDispatchEnabled(profile: SubAgentDispatchProfile): Boolean = profile != SubAgentDispatchProfile.NEVER

    /** Clamp [maxTrips] to the profile's ceiling. */
    fun clampMaxTrips(profile: SubAgentDispatchProfile, maxTrips: Int): Int =
        minOf(maxTrips, caps(profile).maxTripsCeiling)

    /** Clamp [timeoutSeconds] to the profile's ceiling. */
    fun clampTimeout(profile: SubAgentDispatchProfile, timeoutSeconds: Int): Int =
        minOf(timeoutSeconds, caps(profile).timeoutCeilingSec)
}
