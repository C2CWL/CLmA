package me.rerere.rikkahub.subagent

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
 * - [NORMAL]: the previous default. Caps match the old compiled defaults (global 30, per-assistant 3).
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
 * The [NORMAL] values are the old compiled defaults (global 30 = [SubAgentDefaults.GLOBAL_CONCURRENCY_CAP],
 * per-assistant 3 = [SubAgentDefaults.DEFAULT_MAX_TRIPS]'s sibling, slot wait 30s, trips 30, timeout 1800s)
 * so an install that never changes the profile behaves exactly as before.
 */
object SubAgentDispatchProfileRules {

    /**
     * Process-scoped live profile, set by ChatService before tool construction (non-suspend
     * factory reads this). Mirrors [me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits] pattern.
     */
    @Volatile private var liveProfile: SubAgentDispatchProfile = SubAgentDispatchProfile.NEVER

    fun setLiveDispatchProfile(p: SubAgentDispatchProfile) { liveProfile = p }
    fun getLiveDispatchProfile(): SubAgentDispatchProfile = liveProfile

    fun caps(profile: SubAgentDispatchProfile): DispatchCaps = when (profile) {
        SubAgentDispatchProfile.NEVER -> DispatchCaps(0, 0, 0, 0, 0, DescriptionTone.NEVER)
        SubAgentDispatchProfile.ASSIST -> DispatchCaps(4, 1, 10_000L, 8, 300, DescriptionTone.ASSIST)
        SubAgentDispatchProfile.NORMAL -> DispatchCaps(30, 3, 30_000L, 30, 1800, DescriptionTone.NORMAL)
        SubAgentDispatchProfile.BURST -> DispatchCaps(48, 8, 60_000L, 30, 1800, DescriptionTone.BURST)
        SubAgentDispatchProfile.UNLEASHED -> DispatchCaps(64, 16, 120_000L, 60, 3600, DescriptionTone.UNLEASHED)
    }

    /**
     * The effective per-assistant cap, combining the profile ceiling with the assistant's own
     * [me.rerere.rikkahub.data.model.Assistant.maxConcurrentSubAgents] field as a further tighten.
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
