package me.rerere.rikkahub.data.ai.tools

/**
 * The **host-owned section** of an app playbook document.
 *
 * A playbook is one cold-memory Markdown file per package (see [AppPlaybookRules.fileNameFor]).
 * Two writers touch it, and they must not fight over it:
 *
 *  - the **agent**, through `memory_write` — curated prose, optional;
 *  - the **host**, after every turn that actually drove the app — a delimited block of raw
 *    observations, so recall works even on a model running without deep thinking that never
 *    remembers to write anything.
 *
 * This object owns the split rule: everything outside the markers is prose and is preserved
 * verbatim; everything between them belongs to the host and is replaced wholesale on each write.
 * Pure — no IO, no Android — so the merge contract is unit-testable.
 */
object AppPlaybookFile {

    /** Opens the host block. Kept HTML-comment shaped so it renders invisibly in a Markdown view. */
    const val AUTO_BEGIN = "<!-- auto:begin -->"

    /** Closes the host block. */
    const val AUTO_END = "<!-- auto:end -->"

    /** Heading written above the host block. */
    const val AUTO_HEADING = "## 观测记录（宿主自动维护）"

    /** How many observation lines the host block keeps; the oldest are dropped. */
    const val MAX_AUTO_LINES = 24

    /**
     * Splits [text] into `(prose, autoLines)`.
     *
     * A document without both markers (or with them out of order) is treated as all prose and no
     * lines, so a hand-edited or truncated file degrades to "the host rewrites nothing" instead
     * of losing content. Only lines that look like list items are read back, so the heading and
     * stray blank lines cannot accumulate.
     */
    fun split(text: String): Pair<String, List<String>> {
        val begin = text.indexOf(AUTO_BEGIN)
        val end = text.indexOf(AUTO_END)
        if (begin < 0 || end < 0 || end < begin) return text.trim() to emptyList()
        val prose = (
            text.substring(0, begin) +
                "\n" +
                text.substring(end + AUTO_END.length)
            ).trim()
        val lines = text.substring(begin + AUTO_BEGIN.length, end)
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .toList()
        return prose to lines
    }

    /**
     * Appends [fresh] observations to [existing], de-duplicating while keeping the **newest**
     * occurrence (so a repeat moves to the end rather than being ignored) and capping the result
     * at [MAX_AUTO_LINES] so the block cannot grow without bound.
     */
    fun mergeLines(existing: List<String>, fresh: List<String>): List<String> =
        (existing + fresh).distinct().takeLast(MAX_AUTO_LINES)

    /**
     * Renders the final document: prose first, then the host block, if either is non-empty.
     * Always ends with a single newline so repeated rewrites are byte-stable.
     */
    fun render(prose: String, autoLines: List<String>): String {
        val body = buildString {
            if (prose.isNotBlank()) {
                append(prose.trim())
                append("\n\n")
            }
            if (autoLines.isNotEmpty()) {
                append(AUTO_BEGIN)
                append('\n')
                append(AUTO_HEADING)
                append('\n')
                autoLines.forEach {
                    append(it)
                    append('\n')
                }
                append(AUTO_END)
                append('\n')
            }
        }
        return body.trimEnd() + "\n"
    }
}
