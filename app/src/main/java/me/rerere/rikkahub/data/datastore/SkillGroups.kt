package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A user-created group of skills. Lives in [Settings.skillGroups] and is the unit for
 * the three bulk operations on the Skills page: enable-all, disable-all, delete-all.
 *
 * A skill can belong to at most one group — [SkillGroupRules.assignToGroup] enforces
 * this by removing the skill from any other group before adding it to the target. The
 * "ungrouped" section on the Skills page is simply every skill whose name appears in no
 * group.
 *
 * Core skills (see [SkillGroupRules.CORE_SKILL_NAMES]: `agent-core`, `autonomous-agent`)
 * are never offered for grouping: they are the system's own persona and must stay
 * unconditionally active. [SkillGroupRules.canGroupSkill] is the gate the UI consults before
 * listing a skill as selectable.
 *
 * The `enabled` flag is the group's on/off switch. When `false`, every skill in
 * [skillNames] is suppressed from the AI's available-skills surface even if the
 * assistant has it in its `enabledSkills` — see
 * [SkillGroupRules.disabledSkillNames] / [SkillGroupRules.effectiveEnabledSkills].
 */
@Serializable
data class SkillGroup(
    val id: String,
    val name: String,
    @SerialName("skill_names")
    val skillNames: Set<String> = emptySet(),
    val enabled: Boolean = true,
)

/**
 * Pure functions over [SkillGroup] lists. Kept free of Android types so the behaviour is
 * unit-testable without a device (see `SkillGroupRulesTest`). The UI consults
 * [canGroupSkill] with a skill's name to decide what the group editor may offer.
 */
object SkillGroupRules {

    /**
     * The app's own persona skills. Bundled, auto-loaded into the system prompt every turn,
     * and what teaches the model how to behave — disabling or grouping them would break the
     * agent, so the Skills page pins them in a locked section. Kept as an explicit name set
     * (rather than "any `auto_load: true` skill") so a user's own auto-load skill can still
     * be grouped and switched like any other.
     */
    val CORE_SKILL_NAMES = setOf("agent-core", "autonomous-agent")

    /** Core skills are pinned; every other skill may be put in a group. */
    fun canGroupSkill(skillName: String): Boolean = skillName !in CORE_SKILL_NAMES

    /**
     * The set of skill names suppressed by disabled groups. A skill is suppressed iff it
     * is a member of any group whose [SkillGroup.enabled] is `false`. Membership in a
     * disabled group is the only suppression vector; an enabled group never suppresses.
     */
    fun disabledSkillNames(groups: List<SkillGroup>): Set<String> =
        groups.filterNot { it.enabled }.flatMap { it.skillNames }.toSet()

    /**
     * Subtract [disabledSkillNames] from the assistant's enabled-skills set. This is the
     * value that should reach [me.rerere.rikkahub.data.ai.tools.createSkillTools] so a
     * disabled group's skills never surface to the model.
     */
    fun effectiveEnabledSkills(
        enabledSkills: Set<String>,
        groups: List<SkillGroup>,
    ): Set<String> = enabledSkills - disabledSkillNames(groups)

    /**
     * Add [skillName] to group [groupId], first removing it from any other group so a
     * skill has at most one home. Returns the updated list; the original is untouched.
     * If [groupId] is not found the list is returned unchanged.
     */
    fun assignToGroup(
        groups: List<SkillGroup>,
        groupId: String,
        skillName: String,
    ): List<SkillGroup> {
        val target = groups.firstOrNull { it.id == groupId } ?: return groups
        // Pull the skill out of every group first (target included, so we then add it
        // back exactly once — keeps the insertion order stable and avoids duplicates).
        val cleared = groups.map { g ->
            if (g.skillNames.contains(skillName)) g.copy(skillNames = g.skillNames - skillName) else g
        }
        val renamedTarget = target.copy(skillNames = target.skillNames + skillName)
        return cleared.map { g -> if (g.id == groupId) renamedTarget else g }
    }

    /**
     * Remove [skillName] from whichever group holds it. Returns the updated list; the
     * original is untouched. A skill not in any group is a no-op.
     */
    fun removeFromGroup(
        groups: List<SkillGroup>,
        skillName: String,
    ): List<SkillGroup> = groups.map { g ->
        if (g.skillNames.contains(skillName)) g.copy(skillNames = g.skillNames - skillName) else g
    }

    /**
     * Toggle [SkillGroup.enabled] for the group with [groupId]. Returns the updated list;
     * the original is untouched. Unknown id is a no-op.
     */
    fun setGroupEnabled(
        groups: List<SkillGroup>,
        groupId: String,
        enabled: Boolean,
    ): List<SkillGroup> = groups.map { g ->
        if (g.id == groupId) g.copy(enabled = enabled) else g
    }

    /**
     * Rename the group with [groupId]. Returns the updated list; the original is
     * untouched. Unknown id is a no-op.
     */
    fun renameGroup(
        groups: List<SkillGroup>,
        groupId: String,
        name: String,
    ): List<SkillGroup> = groups.map { g ->
        if (g.id == groupId) g.copy(name = name) else g
    }

    /**
     * Delete the group definition only — its skills stay on disk and fall back into the
     * "ungrouped" section. Use [reassignAll] + filter when the caller also wants to drop
     * the skills themselves.
     */
    fun deleteGroup(
        groups: List<SkillGroup>,
        groupId: String,
    ): List<SkillGroup> = groups.filterNot { it.id == groupId }

    /** All skill names across every group (enabled or not). */
    fun groupedSkillNames(groups: List<SkillGroup>): Set<String> =
        groups.flatMap { it.skillNames }.toSet()

    /**
     * Names in [allSkillNames] that are neither grouped nor core. Drives the
     * "ungrouped" section of the Skills page.
     *
     * [coreSkillNames] are the auto-load skills; they are excluded from the ungrouped
     * list because the page shows them in their own locked "core" section.
     */
    fun ungroupedSkillNames(
        allSkillNames: List<String>,
        groups: List<SkillGroup>,
        coreSkillNames: Set<String>,
    ): List<String> {
        val grouped = groupedSkillNames(groups)
        return allSkillNames.filter { it !in grouped && it !in coreSkillNames }
    }
}
