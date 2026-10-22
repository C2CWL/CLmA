package me.rerere.search

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultLimitsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `stored options without the new fields keep their defaults`() {
        val decoded = json.decodeFromString<SearchCommonOptions>("""{"resultSize":4}""")

        assertEquals(4, decoded.resultSize)
        assertEquals(DEFAULT_SNIPPET_MAX_CHARS, decoded.snippetMaxChars)
        assertEquals(DEFAULT_SCRAPE_MAX_CHARS, decoded.scrapeMaxChars)
    }

    @Test
    fun `text within the cap is unchanged`() {
        assertEquals("short snippet", capText("short snippet", 800, hintScrape = true))
    }

    @Test
    fun `non positive cap disables truncation`() {
        val text = "x".repeat(5_000)
        assertEquals(text, capText(text, 0, hintScrape = true))
        assertEquals(text, capText(text, -1, hintScrape = false))
    }

    @Test
    fun `over cap cuts on a sentence boundary and reports the exact omission`() {
        val text = "x".repeat(70) + ". " + "y".repeat(200)
        val capped = capText(text, 100, hintScrape = true)

        assertTrue(capped.startsWith("x".repeat(70) + "."))
        assertTrue(capped.contains("use scrape_web"))
        assertOmissionMatches(text, capped)
    }

    @Test
    fun `a boundary in the first half does not pull the cut back`() {
        val text = "x. " + "y".repeat(200)
        val capped = capText(text, 100, hintScrape = false)

        assertFalse(capped.contains("scrape_web"))
        val kept = capped.substringBefore("…")
        assertEquals(100, kept.length)
        assertOmissionMatches(text, capped)
    }

    @Test
    fun `cap does not split an emoji`() {
        val text = "a".repeat(10) + "\uD83D\uDE00" + "b".repeat(40)
        val capped = capText(text, 11, hintScrape = false)
        val kept = capped.substringBefore("…")

        assertTrue(text.startsWith(kept))
        assertFalse(kept.endsWith("\uD83D"))
        assertOmissionMatches(text, capped)
    }

    @Test
    fun `search limit caps snippets and leaves a fitting answer alone`() {
        val result = SearchResult(
            answer = "A".repeat(100),
            items = listOf(
                SearchResult.SearchResultItem(
                    title = "Title",
                    url = "https://example.com",
                    text = "B".repeat(500),
                    publishedDate = "2026-10-01",
                    highlights = listOf("C".repeat(500)),
                )
            ),
            images = listOf("https://example.com/a.png"),
        )

        val capped = result.limitedBy(SearchCommonOptions(resultSize = 3, snippetMaxChars = 80))

        assertEquals("Title", capped.items.single().title)
        assertEquals("https://example.com", capped.items.single().url)
        assertEquals("2026-10-01", capped.items.single().publishedDate)
        assertEquals(result.images, capped.images)
        assertEquals(result.answer, capped.answer)
        assertTrue(capped.items.single().text.contains("scrape_web"))
        assertTrue(capped.items.single().text.length < 500)
        assertFalse(capped.items.single().highlights.single().contains("scrape_web"))
        assertTrue(capped.items.single().highlights.single().contains("chars omitted"))
    }

    @Test
    fun `synthesized answer uses four times the snippet cap`() {
        val result = SearchResult(
            answer = "A".repeat(400),
            items = listOf(SearchResult.SearchResultItem("t", "u", "short")),
        )

        val capped = result.limitedBy(SearchCommonOptions(snippetMaxChars = 50))
        val answer = checkNotNull(capped.answer)

        assertEquals("short", capped.items.single().text)
        assertTrue(answer.contains("chars omitted"))
        assertFalse(answer.contains("scrape_web"))
        assertOmissionMatches("A".repeat(400), answer)
    }

    @Test
    fun `zero snippet cap leaves the result untouched`() {
        val result = SearchResult(
            answer = "A".repeat(10_000),
            items = listOf(SearchResult.SearchResultItem("t", "u", "B".repeat(10_000))),
        )

        assertEquals(result, result.limitedBy(SearchCommonOptions(snippetMaxChars = 0)))
    }

    @Test
    fun `scrape cap trims page bodies only`() {
        val scraped = ScrapedResult(
            urls = listOf(
                ScrapedResultUrl(
                    url = "https://example.com",
                    content = "D".repeat(1_000),
                    metadata = ScrapedResultMetadata(title = "Page"),
                )
            )
        )

        val capped = scraped.limitedBy(SearchCommonOptions(scrapeMaxChars = 100))

        assertEquals("https://example.com", capped.urls.single().url)
        assertEquals("Page", capped.urls.single().metadata?.title)
        assertTrue(capped.urls.single().content.contains("chars omitted"))
        assertFalse(capped.urls.single().content.contains("scrape_web"))
        assertEquals(
            scraped,
            scraped.limitedBy(SearchCommonOptions(scrapeMaxChars = 0)),
        )
    }

    @Test
    fun `exa requests full text only when the cap is off and no evidence filter is set`() {
        val open = exaContentsSpec(snippetMaxChars = 0, evidenceMode = false)
        assertTrue(open.unboundedText)
        assertEquals(0, open.highlightMaxCharacters)

        val evidence = exaContentsSpec(snippetMaxChars = 0, evidenceMode = true)
        assertFalse(evidence.unboundedText)
        assertEquals(EXA_LEGACY_TEXT_CHARACTERS, evidence.textMaxCharacters)
        assertEquals(EXA_HIGHLIGHT_MAX_CHARACTERS, evidence.highlightMaxCharacters)
    }

    @Test
    fun `exa honours the snippet cap and clamps it to the api maximum`() {
        val normal = exaContentsSpec(snippetMaxChars = 800, evidenceMode = false)
        assertFalse(normal.unboundedText)
        assertEquals(800, normal.textMaxCharacters)
        assertEquals(800, normal.highlightMaxCharacters)

        val evidence = exaContentsSpec(snippetMaxChars = 500, evidenceMode = true)
        assertEquals(500, evidence.textMaxCharacters)
        assertEquals(500, evidence.highlightMaxCharacters)

        val wide = exaContentsSpec(snippetMaxChars = 50_000, evidenceMode = false)
        assertEquals(EXA_API_MAX_TEXT_CHARACTERS, wide.textMaxCharacters)
        assertEquals(EXA_HIGHLIGHT_MAX_CHARACTERS, wide.highlightMaxCharacters)

        assertEquals(EXA_LEGACY_TEXT_CHARACTERS, exaScrapeMaxCharacters(0))
        assertEquals(3_000, exaScrapeMaxCharacters(3_000))
        assertEquals(EXA_API_MAX_TEXT_CHARACTERS, exaScrapeMaxCharacters(80_000))
    }

    private fun assertOmissionMatches(original: String, capped: String) {
        val kept = capped.substringBefore("…")
        val omitted = Regex("""\[(\d+) chars omitted""").find(capped)!!.groupValues[1].toInt()
        assertTrue(original.startsWith(kept))
        assertEquals(original.length - kept.length, omitted)
    }
}
