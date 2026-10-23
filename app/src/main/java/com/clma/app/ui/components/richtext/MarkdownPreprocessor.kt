package com.clma.app.ui.components.richtext

private val MARKDOWN_CODE_SPAN_OR_FENCE_REGEX = Regex(
    "```[\\s\\S]*?```|`[^`\\n]*`",
    RegexOption.DOT_MATCHES_ALL,
)
private val NUMERIC_RANGE_TILDE_REGEX = Regex("(?<=[0-9万亿千百十kKmMbB元])~(?=[0-9万亿千百十kKmMbB元])")

/**
 * Keep tildes between numeric values literal instead of letting GFM pair them
 * with a later tilde and render an unexpectedly long strikethrough.
 */
internal fun protectNumericRangeTildes(content: String): String {
    val codeRanges = MARKDOWN_CODE_SPAN_OR_FENCE_REGEX.findAll(content).map { it.range }.toList()
    return NUMERIC_RANGE_TILDE_REGEX.replace(content) { match ->
        if (codeRanges.any { match.range.first in it }) match.value else "&#126;"
    }
}
