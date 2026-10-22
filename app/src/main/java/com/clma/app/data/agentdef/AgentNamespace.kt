package me.rerere.rikkahub.data.agentdef

import me.rerere.rikkahub.data.agentdef.AgentDefinitionDefaults.COLD_MEMORY_FOLDER
import me.rerere.rikkahub.data.agentdef.AgentDefinitionDefaults.NAMESPACE_ROOT

/**
 * D9 — pure slug / path arithmetic for an expert's private namespace.
 *
 * Kept as an object of pure functions (no Room, no Android, no `Assistant`) so the rule
 * "what may a namespace path contain" is unit-testable on a plain JVM, which matters because
 * the value ends up as a **file path** under the user's workspace. The rule is deliberately
 * narrow: lowercase ASCII letters, digits and single dashes; no dots at all (so `..` cannot be
 * spelled), no separators, length-bounded. Anything that does not fit is normalised into
 * something that does, and [isValidSlug] is what the write path will re-check.
 *
 * A dash in the input is a **separator**, not a literal: `Deep -- Research` becomes
 * `deep-research`, not `deep----research`. Otherwise a user typing two dashes would silently
 * get a different namespace than one typing one, and the paths are user-visible.
 */
object AgentNamespace {

    /** Longest slug we will store; the DB column is unbounded, this is the product rule. */
    const val MAX_SLUG_LENGTH = 48

    /** Characters that survive into a slug verbatim. */
    private val KEPT = Regex("[a-z0-9]")

    /** Characters a finished slug may consist of (`-` allowed, but not at either end). */
    private val VALID = Regex("[a-z0-9-]")

    /**
     * Derives a slug from a human name: case-folded, every run of separators collapsed to a
     * single dash, dashes never leading or trailing, truncated to [MAX_SLUG_LENGTH].
     *
     * Returns `""` when the name carries no usable character at all (e.g. a name written
     * entirely in a non-Latin script) — callers must then ask the user for an explicit slug
     * rather than inventing one, because two experts silently sharing a namespace would mix
     * their memory files.
     */
    fun slugify(name: String): String {
        val lowered = name.trim().lowercase()
        val sb = StringBuilder(lowered.length)
        var pendingSeparator = false
        for (ch in lowered) {
            if (KEPT.matches(ch.toString())) {
                if (pendingSeparator && sb.isNotEmpty()) sb.append('-')
                pendingSeparator = false
                sb.append(ch)
            } else {
                pendingSeparator = true
            }
        }
        return truncate(sb.toString())
    }

    /** True when [slug] is exactly what [slugify] / the edit UI is allowed to store. */
    fun isValidSlug(slug: String?): Boolean {
        if (slug.isNullOrEmpty()) return false
        if (slug.length > MAX_SLUG_LENGTH) return false
        if (slug.startsWith('-') || slug.endsWith('-')) return false
        return slug.all { VALID.matches(it.toString()) }
    }

    /** Normalises a user-typed slug, or null when nothing usable is left. */
    fun normalizeSlug(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty()) return null
        val cleaned = truncate(trimmed)
        return if (isValidSlug(cleaned)) cleaned else slugify(cleaned).ifEmpty { null }
    }

    /** `agents/<slug>` — the expert's private directory inside the parent workspace. */
    fun namespaceDirFor(slug: String?): String? {
        val valid = normalizeSlug(slug) ?: return null
        return "$NAMESPACE_ROOT/$valid"
    }

    /**
     * `agents/<slug>/memory` — what goes into the synthesised assistant's `coldMemoryDir`.
     * Relative on purpose: `ColdMemoryRules.normalizeDir` resolves it against whichever
     * workspace the run is bound to, so an expert follows its parent's workspace instead of
     * pinning one.
     */
    fun coldMemoryDirFor(slug: String?): String? {
        val dir = namespaceDirFor(slug) ?: return null
        return "$dir/$COLD_MEMORY_FOLDER"
    }

    /**
     * Trims to [MAX_SLUG_LENGTH] and drops any dash the cut exposed. [slugify] never emits a
     * trailing dash, but a cut at exactly the limit can land one there (the dash that would
     * have preceded the next character).
     */
    private fun truncate(value: String): String =
        (if (value.length <= MAX_SLUG_LENGTH) value else value.take(MAX_SLUG_LENGTH)).trim('-')
}
