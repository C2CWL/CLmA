package me.rerere.rikkahub.data.datastore

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
     * Compact action chips. Tool-only groups drop the timeline; a group that also
     * contains reasoning keeps the timeline and only swaps the tool icon and title.
     */
    @SerialName("capsule")
    CAPSULE,
}

/** Which capsule copy a tool gets. Null means the classic title and icon stay. */
enum class CapsuleToolKind {
    SEARCH,
    READ,
    FETCH,
}

fun capsuleToolKind(toolName: String): CapsuleToolKind? = when (toolName) {
    "search_web" -> CapsuleToolKind.SEARCH
    "scrape_web", "web_extract" -> CapsuleToolKind.READ
    "web_fetch" -> CapsuleToolKind.FETCH
    else -> null
}

/**
 * Short label for a capsule title. Web tools prefer the host; everything else is
 * the raw argument, truncated so the chip stays one line.
 */
fun capsuleArgumentLabel(raw: String?, preferHost: Boolean, max: Int = 42): String? {
    val value = raw?.trim().orEmpty()
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
