package me.rerere.rikkahub.data.files

/**
 * The Agent Skills spec's `name` rules, in one place.
 *
 * Upstream defines, for `name`:
 *  - 1..64 characters;
 *  - lowercase letters, digits and hyphens only (`a-z`, `0-9`, `-`);
 *  - must not start or end with a hyphen;
 *  - must not contain consecutive hyphens (`--`);
 *  - must match the parent directory name.
 *
 * This app satisfies the last rule by construction — the skill's directory *is* its
 * `name` (see `SkillManager.saveSkill`, which resolves `skillsDir/<name>`), so a name that
 * passes [isValid] here is always laid out on disk the way the spec expects.
 *
 * The pre-alignment checks were looser in one direction (they accepted `_`) and tighter in
 * another (a 40-character cap). Both are now the spec's: an author following the community
 * conventions gets exactly the name they wrote, and an author who wrote something the spec
 * does not allow gets a *normalised* name rather than a refusal where that is possible
 * (see [normalize]).
 */
object SkillNameRules {
    /** Spec: max 64 characters. */
    const val MAX_LENGTH: Int = 64

    /**
     * Spec: lowercase alphanumerics in hyphen-separated runs — which is also exactly the
     * "no leading/trailing/consecutive hyphens" clause, expressed in one pattern.
     */
    private val ValidName = Regex("""^[a-z0-9]+(?:-[a-z0-9]+)*$""")

    /** True when [name] is spec-valid as written. */
    fun isValid(name: String): Boolean = name.length in 1..MAX_LENGTH && ValidName.matches(name)

    /**
     * Coerce arbitrary author-supplied text (a title, an H1, a legacy name using `_` or
     * uppercase, a 70-character name) into a spec-valid one:
     *
     *  - lowercase;
     *  - every run of characters outside `[a-z0-9]` collapses to a single `-` (so `_`, spaces,
     *    `--`, emoji and punctuation all fold the same way);
     *  - leading/trailing hyphens trimmed;
     *  - truncated to [MAX_LENGTH] *then* re-trimmed, so the cut never leaves a trailing `-`.
     *
     * Returns the empty string when nothing survives (e.g. the input was only punctuation);
     * callers surface that as a refusal rather than inventing a name.
     */
    fun normalize(input: String): String {
        val cleaned = input.trim().lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
        if (cleaned.isEmpty()) return ""
        return cleaned.take(MAX_LENGTH).trim('-')
    }
}
