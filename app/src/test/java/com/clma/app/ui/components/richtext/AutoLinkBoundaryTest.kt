package com.clma.app.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoLinkBoundaryTest {
    @Test
    fun `chinese punctuation and sentence are not part of the link`() {
        val token = "https://github.com/ccch1mneyyy/dsh-go”。结合上下文，用户之前问“CLmA目前可以装插件吗？”"
        val (url, trailing) = splitGfmAutoLink(token)
        assertEquals("https://github.com/ccch1mneyyy/dsh-go", url)
        assertEquals("”。结合上下文，用户之前问“CLmA目前可以装插件吗？”", trailing)
        assertEquals(token, url + trailing)
    }

    @Test
    fun `chinese text directly after url is not part of the link`() {
        val token = "https://example.com/path而且后面还有文字"
        val (url, trailing) = splitGfmAutoLink(token)
        assertEquals("https://example.com/path", url)
        assertEquals("而且后面还有文字", trailing)
    }

    @Test
    fun `plain ascii url is unchanged`() {
        val token = "https://example.com/a/b?c=1#d"
        val (url, trailing) = splitGfmAutoLink(token)
        assertEquals(token, url)
        assertEquals("", trailing)
    }

    @Test
    fun `trailing ascii punctuation is excluded from the link`() {
        val (url, trailing) = splitGfmAutoLink("https://example.com/path.")
        assertEquals("https://example.com/path", url)
        assertEquals(".", trailing)
    }

    @Test
    fun `www autolink with chinese suffix`() {
        val token = "www.example.com/page，下一句"
        val (url, trailing) = splitGfmAutoLink(token)
        assertEquals("www.example.com/page", url)
        assertEquals("，下一句", trailing)
    }
}
