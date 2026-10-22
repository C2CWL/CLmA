package me.rerere.rikkahub.data.files

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

object SkillFrontmatterParser {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    fun parse(content: String): SkillFrontmatter {
        if (!content.startsWith("---")) return SkillFrontmatter.Empty
        val endRange = findFrontmatterEndRange(content) ?: return SkillFrontmatter.Empty
        val yamlContent = content.substring(3, endRange.first).trim()
        if (yamlContent.isEmpty()) return SkillFrontmatter.Empty

        return runCatching {
            val values = createYaml().load<Any?>(yamlContent) as? Map<*, *>
                ?: return SkillFrontmatter.Empty
            SkillFrontmatter(
                values.entries.mapNotNull { (key, value) ->
                    (key as? String)?.let { it to value }
                }.toMap()
            )
        }.getOrDefault(SkillFrontmatter.Empty)
    }

    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }

    private fun createYaml(): Yaml {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
            codePointLimit = 1_000_000
        }
        return Yaml(SafeConstructor(options))
    }
}

/**
 * A parsed SKILL.md YAML frontmatter block.
 *
 * Values keep their *scalar* shape: YAML gives back `Boolean` for `auto_load: true` and
 * `Double` for `version: 1.0`, which is what the Agent Skills spec's `metadata:` map
 * (a map from string keys to string values) routinely contains. The ubiquitous
 * `get(key)` accessor therefore **coerces scalars to their textual form** rather than
 * dropping everything that is not already a `String`.
 *
 * This is a correctness fix, not a nicety: with the old `as? String` filter the shipped
 * `auto_load: true` (a YAML boolean) silently degraded to `null`, so `SkillMetadata.autoLoad`
 * was *always* false — the "core persona" skills (`agent-core`, `autonomous-agent`) were
 * listed as lazy skills instead of being inlined into the system prompt, and the seeding
 * path in `SkillManager.seedDefaultSkillsIfNeeded` never classified them as core either.
 */
class SkillFrontmatter internal constructor(
    private val values: Map<String, Any?>,
) {
    /**
     * Scalar accessor. Strings are returned as-is; booleans and numbers are rendered with
     * `toString()` so `auto_load: true` reads back as `"true"` and `version: 1.0` as `"1.0"`.
     * Collections (the spec's `metadata:` mapping, a YAML list) are *not* stringified —
     * use [map] / [list] for those.
     */
    operator fun get(key: String): String? = when (val value = values[key]) {
        is String -> value
        is Boolean, is Number -> value.toString()
        else -> null
    }

    /** Explicit alias for [get] — reads better at call sites that want a string back. */
    fun string(key: String): String? = get(key)

    /**
     * Boolean accessor that understands both the YAML-native form (`auto_load: true`) and a
     * quoted string (`auto_load: "true"`). Anything else (including a missing key) yields
     * [default], so a typo in a skill's frontmatter never accidentally enables a feature.
     */
    fun boolean(key: String, default: Boolean = false): Boolean = when (val value = values[key]) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "yes", "on" -> true
            "false", "no", "off" -> false
            else -> default
        }
        else -> default
    }

    /**
     * Reads the spec's `allowed-tools` field, which is defined as a **space-separated string**
     * (`allowed-tools: Read Write Bash(git:*)`). A YAML list is accepted too — third-party
     * authors routinely write one — and a comma inside a string is treated as a separator as
     * well, since that is the only other shape seen in the wild. Advisory only: the app never
     * restricts a skill's tool surface from this field (see `SkillMetadata.allowedTools`).
     */
    fun list(key: String): List<String> = when (val value = values[key]) {
        is List<*> -> value.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        is String -> value.split(' ', ',', '\n', '\t').filter(String::isNotBlank)
        else -> emptyList()
    }

    /**
     * Reads a string→string mapping (`metadata:`). Non-string keys are dropped; non-string
     * values are coerced with `toString()` (YAML types `version: 1.0` as a number, and the
     * spec wants a string there anyway).
     */
    fun map(key: String): Map<String, String> = when (val value = values[key]) {
        is Map<*, *> -> value.entries.mapNotNull { (k, v) ->
            val name = (k as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val text = v?.toString() ?: return@mapNotNull null
            name to text
        }.toMap()
        else -> emptyMap()
    }

    /** The untouched YAML value — for diagnostics/tests, never for feature decisions. */
    fun raw(key: String): Any? = values[key]

    companion object {
        internal val Empty = SkillFrontmatter(emptyMap())
    }
}
