package com.clma.app.service.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBoxMarkdownTest {

    @Test
    fun `empty text returns empty result`() {
        val result = ChatBoxMarkdown.parse("")
        assertEquals("", result.text)
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun `plain text has no spans and unchanged content`() {
        val raw = "Hello world! 1 + 2 = 3 * 4"
        val result = ChatBoxMarkdown.parse(raw)
        assertEquals(raw, result.text)
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun `inline bold with double asterisks or double underscores`() {
        val raw = "This is **bold** and __also bold__."
        val result = ChatBoxMarkdown.parse(raw)
        // raw 末尾只有一个 '.', 去标记后自然只有一个句点
        assertEquals("This is bold and also bold.", result.text)
        assertEquals(
            listOf(
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.BOLD, 8, 12),
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.BOLD, 17, 26),
            ),
            result.spans,
        )
    }

    @Test
    fun `inline code strips backticks and marks monospace span`() {
        val raw = "Run `git status` now"
        val result = ChatBoxMarkdown.parse(raw)
        assertEquals("Run git status now", result.text)
        assertEquals(
            listOf(
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.CODE, 4, 14),
            ),
            result.spans,
        )
    }

    @Test
    fun `fenced code block strips fences and preserves inner lines`() {
        val raw = "Start\n```kotlin\nval a = 1\nval b = 2\n```\nEnd"
        val result = ChatBoxMarkdown.parse(raw)
        assertEquals("Start\nval a = 1\nval b = 2\nEnd", result.text)
        assertEquals(
            listOf(
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.CODE_BLOCK, 6, 25),
            ),
            result.spans,
        )
    }

    @Test
    fun `header strips hash symbols and applies bold span`() {
        val raw = "# Title 1\n## Title 2\nContent"
        val result = ChatBoxMarkdown.parse(raw)
        assertEquals("Title 1\nTitle 2\nContent", result.text)
        assertEquals(
            listOf(
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.BOLD, 0, 7),
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.BOLD, 8, 15),
            ),
            result.spans,
        )
    }

    @Test
    fun `strikethrough and italic formatting`() {
        val raw = "~~deleted~~ and *slanted*"
        val result = ChatBoxMarkdown.parse(raw)
        assertEquals("deleted and slanted", result.text)
        assertEquals(
            listOf(
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.STRIKE, 0, 7),
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.ITALIC, 12, 19),
            ),
            result.spans,
        )
    }

    @Test
    fun `bold italic triple asterisks formatting`() {
        val raw = "Important: ***very important***"
        val result = ChatBoxMarkdown.parse(raw)
        assertEquals("Important: very important", result.text)
        assertEquals(
            listOf(
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.BOLD_ITALIC, 11, 25),
            ),
            result.spans,
        )
    }

    @Test
    fun `streaming unclosed bold or code remains literal without crashing`() {
        val rawBold = "Streaming **not finished yet"
        val resBold = ChatBoxMarkdown.parse(rawBold)
        assertEquals("Streaming **not finished yet", resBold.text)
        assertTrue(resBold.spans.isEmpty())

        val rawCode = "Incomplete `code snippet"
        val resCode = ChatBoxMarkdown.parse(rawCode)
        assertEquals("Incomplete `code snippet", resCode.text)
        assertTrue(resCode.spans.isEmpty())
    }

    @Test
    fun `streaming unclosed code block formats till end of buffer`() {
        val raw = "Prefix\n```python\nprint('hello')\n"
        val result = ChatBoxMarkdown.parse(raw)
        assertEquals("Prefix\nprint('hello')\n", result.text)
        assertEquals(
            listOf(
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.CODE_BLOCK, 7, 22),
            ),
            result.spans,
        )
    }

    @Test
    fun `nested inline code inside bold`() {
        val raw = "Look at **`foo()` method** here"
        val result = ChatBoxMarkdown.parse(raw)
        assertEquals("Look at foo() method here", result.text)
        assertEquals(
            listOf(
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.CODE, 8, 13),
                ChatBoxMarkdown.Span(ChatBoxMarkdown.SpanType.BOLD, 8, 20),
            ),
            result.spans,
        )
    }
}
