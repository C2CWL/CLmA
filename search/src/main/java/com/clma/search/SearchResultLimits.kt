package me.rerere.search

import kotlinx.serialization.Serializable

/**
 * Bounds what a search provider hands back to the model.
 *
 * Some providers (Exa with `contents.text = true`, Tavily `content`, Zhipu, …) put an entire
 * page into one snippet. Ten of those overflow the tool-result budget, the JSON is cut
 * mid-structure, and the model only ever sees the first hit — then searches again.
 * Capping each snippet, and telling a provider that accepts a cap to stop there, keeps every
 * hit intact. Full pages stay on scrape, which has its own larger cap.
 */

/** Exa's `contents.text.maxCharacters` accepts 1..10000. Above that the API rejects the call. */
internal const val EXA_API_MAX_TEXT_CHARACTERS = 10_000

internal const val EXA_HIGHLIGHT_MAX_CHARACTERS = 1_200

/** What an unbounded Exa evidence search, and an unbounded scrape, used to request. */
internal const val EXA_LEGACY_TEXT_CHARACTERS = 8_000

const val DEFAULT_SNIPPET_MAX_CHARS = 800

const val DEFAULT_SCRAPE_MAX_CHARS = 8_000

/** A synthesized answer (Tavily, Grok, …) may be longer than one snippet, but not unbounded. */
const val SEARCH_ANSWER_CAP_MULTIPLIER = 4

@Serializable
data class SearchCommonOptions(
    val resultSize: Int = 10,
    /** Per-result snippet cap, in characters. 0 disables the cap. */
    val snippetMaxChars: Int = DEFAULT_SNIPPET_MAX_CHARS,
    /** Per-page scrape cap, in characters. 0 disables the cap. */
    val scrapeMaxChars: Int = DEFAULT_SCRAPE_MAX_CHARS,
)

@Serializable
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
    val images: List<String> = emptyList(),
    /** Local time at which this result was retrieved; not a publication date. */
    val retrievedAt: String? = null,
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
        /** Provider-supplied publication date. Null means that no date was supplied. */
        val publishedDate: String? = null,
        val highlights: List<String> = emptyList(),
    )
}

@Serializable
data class ScrapedResult(
    val urls: List<ScrapedResultUrl>,
    /** Local time at which this result was retrieved; not a publication date. */
    val retrievedAt: String? = null,
)

@Serializable
data class ScrapedResultUrl(
    val url: String,
    val content: String,
    val metadata: ScrapedResultMetadata? = null,
)

@Serializable
data class ScrapedResultMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
    /** Provider-supplied publication date. Null means that no date was supplied. */
    val publishedDate: String? = null,
)

/**
 * How to fill Exa's `contents` object.
 *
 * [unboundedText] sends `text: true` (the whole page). Otherwise send `text.maxCharacters`
 * and, when [highlightMaxCharacters] is positive, a highlights block.
 */
internal data class ExaContentsSpec(
    val unboundedText: Boolean,
    val textMaxCharacters: Int,
    val highlightMaxCharacters: Int,
)

internal fun exaContentsSpec(snippetMaxChars: Int, evidenceMode: Boolean): ExaContentsSpec {
    if (snippetMaxChars <= 0 && !evidenceMode) {
        return ExaContentsSpec(
            unboundedText = true,
            textMaxCharacters = 0,
            highlightMaxCharacters = 0,
        )
    }
    val textCap = if (snippetMaxChars <= 0) {
        EXA_LEGACY_TEXT_CHARACTERS
    } else {
        snippetMaxChars.coerceIn(1, EXA_API_MAX_TEXT_CHARACTERS)
    }
    return ExaContentsSpec(
        unboundedText = false,
        textMaxCharacters = textCap,
        highlightMaxCharacters = minOf(EXA_HIGHLIGHT_MAX_CHARACTERS, textCap),
    )
}

/** Characters to ask Exa for when scraping. 0 keeps the historical 8000 request. */
internal fun exaScrapeMaxCharacters(scrapeMaxChars: Int): Int =
    if (scrapeMaxChars <= 0) EXA_LEGACY_TEXT_CHARACTERS
    else scrapeMaxChars.coerceIn(1, EXA_API_MAX_TEXT_CHARACTERS)

/** Drops snippets (and the synthesized answer) past the user's cap. 0 leaves [this] untouched. */
fun SearchResult.limitedBy(options: SearchCommonOptions): SearchResult {
    val cap = options.snippetMaxChars
    if (cap <= 0) return this
    val answerCap = answerCapFor(cap)
    return copy(
        answer = answer?.let { capText(it, answerCap, hintScrape = false) },
        items = items.map { item ->
            item.copy(
                text = capText(item.text, cap, hintScrape = true),
                highlights = item.highlights.map { capText(it, cap, hintScrape = false) },
            )
        },
    )
}

/** Drops scraped page bodies past the user's cap. 0 leaves [this] untouched. */
fun ScrapedResult.limitedBy(options: SearchCommonOptions): ScrapedResult {
    val cap = options.scrapeMaxChars
    if (cap <= 0) return this
    return copy(
        urls = urls.map { it.copy(content = capText(it.content, cap, hintScrape = false)) },
    )
}

internal fun answerCapFor(snippetMaxChars: Int): Int {
    if (snippetMaxChars <= 0) return 0
    return snippetMaxChars.toLong()
        .times(SEARCH_ANSWER_CAP_MULTIPLIER)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

/**
 * Keeps at most [maxChars] characters of [text].
 *
 * Breaks on a sentence or whitespace boundary when one falls in the second half of the
 * budget, so a cap doesn't slice a word, and never splits a surrogate pair. Appends a short
 * omission note (not counted against the budget — it replaces the dropped tail).
 * [maxChars] <= 0, or a text that already fits, is returned unchanged.
 */
internal fun capText(text: String, maxChars: Int, hintScrape: Boolean): String {
    if (maxChars <= 0 || text.length <= maxChars) return text
    val cut = breakIndex(text, maxChars)
    if (cut <= 0) return omissionNote(text.length, hintScrape)
    val kept = text.substring(0, cut).trimEnd()
    if (kept.isEmpty()) return omissionNote(text.length, hintScrape)
    return kept + omissionNote(text.length - kept.length, hintScrape)
}

private fun omissionNote(omitted: Int, hintScrape: Boolean): String =
    if (hintScrape) {
        "… [$omitted chars omitted; use scrape_web for the full page]"
    } else {
        "… [$omitted chars omitted]"
    }

private fun breakIndex(text: String, maxChars: Int): Int {
    val minBreak = maxChars / 2
    val regionEnd = minOf(maxChars, text.length)
    var cut = minOf(maxChars, text.length)
    if (minBreak < regionEnd) {
        val region = text.substring(minBreak, regionEnd)
        val rel = region.lastIndexOfAny(
            charArrayOf('\n', '。', '.', '！', '!', '？', '?', '；', ';', ' '),
        )
        if (rel >= 0) {
            val boundary = region[rel]
            val includeBoundary = boundary != ' ' && boundary != '\n'
            cut = minBreak + rel + if (includeBoundary) 1 else 0
        }
    }
    cut = cut.coerceIn(0, minOf(maxChars, text.length))
    if (cut in 1 until text.length && text[cut - 1].isHighSurrogate()) cut--
    return cut
}
