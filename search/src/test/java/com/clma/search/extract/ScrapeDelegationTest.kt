package com.clma.search.extract

import com.clma.search.SearchServiceOptions
import com.clma.search.SearXNGService
import org.junit.Assert.assertNotNull
import org.junit.Test

class ScrapeDelegationTest {

    @Test
    fun `searxng advertises scraping parameters so the tool registers`() {
        val options = SearchServiceOptions.SearXNGOptions(url = "https://searx.example")

        // SearchTools.kt gates scrape_web registration on this being non-null.
        assertNotNull(SearXNGService.scrapingParameters(options))
    }
}
