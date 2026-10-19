package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HtmlEntitiesTest {

    @Test
    fun `decodes the reported list item`() {
        assertEquals("<10：写代码容易烦。", HtmlEntities.decode("&lt;10：写代码容易烦。"))
    }

    @Test
    fun `decodes basic named entities`() {
        assertEquals("< > & \" '", HtmlEntities.decode("&lt; &gt; &amp; &quot; &apos;"))
        assertEquals("© — …", HtmlEntities.decode("&copy; &mdash; &hellip;"))
    }

    @Test
    fun `decodes decimal and hex references`() {
        assertEquals("'<<😀", HtmlEntities.decode("&#39;&#60;&#x3C;&#X1F600;"))
    }

    @Test
    fun `decodes only one level`() {
        assertEquals("&lt;", HtmlEntities.decode("&amp;lt;"))
    }

    @Test
    fun `leaves unknown or malformed references alone`() {
        assertEquals("&bogus; &lt &#; & a&b", HtmlEntities.decode("&bogus; &lt &#; & a&b"))
    }

    @Test
    fun `invalid code points become replacement character`() {
        assertEquals("\uFFFD\uFFFD\uFFFD", HtmlEntities.decode("&#0;&#xD800;&#x110000;"))
    }

    @Test
    fun `text without ampersand is returned as is`() {
        val text = "plain <10 text"
        assertSame(text, HtmlEntities.decode(text))
    }
}
