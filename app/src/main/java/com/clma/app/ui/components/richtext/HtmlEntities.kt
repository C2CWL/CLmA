package me.rerere.rikkahub.ui.components.richtext

/**
 * Decodes HTML character references (`&lt;`, `&#39;`, `&#x3C;`, `&copy;` …) in Markdown text.
 *
 * CommonMark treats an entity in ordinary text as the character it names, so `&lt;10` must show
 * `<10`. The intellij-markdown AST keeps the raw source in its TEXT leaves, and the Compose
 * renderer in [MarkdownBlock] appended those leaves verbatim, so models that escape `<` showed
 * the literal `&lt;`. (The HTML path, [MarkdownNew], goes through Jsoup and was already fine.)
 *
 * Only call this on plain TEXT leaves: code spans and code blocks keep entities literally, as
 * CommonMark requires. The output is plain text for a Compose `AnnotatedString`, so it is not
 * re-escaped. Unknown named entities stay as written; invalid code points become U+FFFD.
 */
internal object HtmlEntities {
    private val ENTITY = Regex("&(?:#[xX]([0-9a-fA-F]{1,6})|#([0-9]{1,7})|([A-Za-z][A-Za-z0-9]{1,31}));")

    /** The entities models and users actually produce; anything else is left untouched. */
    private val NAMED = mapOf(
        "lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "ensp" to "\u2002", "emsp" to "\u2003", "thinsp" to "\u2009",
        "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
        "middot" to "·", "bull" to "•", "times" to "×", "divide" to "÷",
        "deg" to "°", "plusmn" to "±", "micro" to "µ", "para" to "¶", "sect" to "§",
        "cent" to "¢", "pound" to "£", "yen" to "¥", "euro" to "€",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔",
        "le" to "≤", "ge" to "≥", "ne" to "≠", "asymp" to "≈", "infin" to "∞",
    )

    fun decode(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { match ->
            val (hex, dec, name) = match.destructured
            when {
                hex.isNotEmpty() -> codePoint(hex.toInt(16))
                dec.isNotEmpty() -> codePoint(dec.toInt())
                else -> NAMED[name] ?: match.value
            }
        }
    }

    private fun codePoint(value: Int): String =
        if (value == 0 || value > 0x10FFFF || value in 0xD800..0xDFFF) {
            "\uFFFD"
        } else {
            String(Character.toChars(value))
        }
}
