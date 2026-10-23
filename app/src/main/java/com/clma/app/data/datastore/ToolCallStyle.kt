package com.clma.app.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * How tool calls are drawn in chat.
 *
 * [CLASSIC] is the existing timeline / log style and the default, so installs that
 * never pick a style — and settings written before this field existed — stay unchanged.
 */
@Serializable
enum class ToolCallStyle {
    /** Timeline / log style. */
    @SerialName("classic")
    CLASSIC,

    /**
     * Compact action chips: no timeline, no icon disc, 20dp corners and the flat
     * surfaceContainer fill for the whole step card.
     *
     * It used to apply only to a pure tool group, but every reasoning model — plus
     * `foldProcessNarration`, which is on by default — puts a reasoning or narration
     * step in the same group, so the capsule look never actually appeared and the
     * setting looked broken. The style now covers the whole card.
     */
    @SerialName("capsule")
    CAPSULE,
}

/** Which capsule copy a tool gets. Null means the classic title and icon stay. */
enum class CapsuleToolKind {
    /** Web search — title shows the query. */
    SEARCH,

    /** Page reader (scrape / extract) — title shows the host. */
    READ,

    /** Page fetcher — title shows the host. */
    FETCH,

    /** Shell command — title shows the command. */
    RUN,

    /** JS skill — title shows the skill name. */
    RUN_JS,

    /** Skill invocation — title shows the skill name. */
    SKILL,

    /** Workspace file read — title shows the path. */
    READ_FILE,

    /** Workspace file write — title shows the path. */
    WRITE_FILE,

    /** Workspace file edit — title shows the path. */
    EDIT_FILE,

    /** Memory tool — title shows what happened (create / edit / delete). */
    MEMORY,
}

fun capsuleToolKind(toolName: String): CapsuleToolKind? = when (toolName) {
    "search_web", "conversation_search" -> CapsuleToolKind.SEARCH
    "scrape_web", "web_extract" -> CapsuleToolKind.READ
    "web_fetch" -> CapsuleToolKind.FETCH
    "workspace_shell" -> CapsuleToolKind.RUN
    "run_js" -> CapsuleToolKind.RUN_JS
    "use_skill" -> CapsuleToolKind.SKILL
    "workspace_read_file" -> CapsuleToolKind.READ_FILE
    "workspace_write_file" -> CapsuleToolKind.WRITE_FILE
    "workspace_edit_file" -> CapsuleToolKind.EDIT_FILE
    "memory_tool" -> CapsuleToolKind.MEMORY
    else -> null
}

/**
 * The argument a capsule title should show for [kind], read through [arg] so this
 * stays free of Compose and JSON types and can be unit tested. Null when the tool
 * omitted that argument — the caller then picks the "nothing yet" copy.
 */
fun capsuleSubject(kind: CapsuleToolKind, arg: (String) -> String?): String? = when (kind) {
    CapsuleToolKind.SEARCH -> arg("query")
    CapsuleToolKind.READ, CapsuleToolKind.FETCH -> arg("url")
    // A multi-line command would break the one-line chip.
    CapsuleToolKind.RUN -> arg("command")?.replace('\n', ' ')
    CapsuleToolKind.RUN_JS -> arg("skill_name") ?: arg("name")
    CapsuleToolKind.SKILL -> arg("name") ?: arg("skill_name")
    CapsuleToolKind.READ_FILE,
    CapsuleToolKind.WRITE_FILE,
    CapsuleToolKind.EDIT_FILE,
    -> arg("path")

    CapsuleToolKind.MEMORY -> arg("action")
}

/**
 * Short label for a capsule title. Web tools prefer the host; everything else is
 * the raw argument, truncated so the chip stays one line.
 */
fun capsuleArgumentLabel(raw: String?, preferHost: Boolean, max: Int = 42): String? {
    val value = raw?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    if (value.isEmpty()) return null
    val shown = if (preferHost) hostOrOriginal(value) else value
    return if (shown.length > max) shown.take(max - 1) + "…" else shown
}

private fun hostOrOriginal(value: String): String {
    val host = runCatching { URI(value).host }.getOrNull()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }
    return host ?: value
}
