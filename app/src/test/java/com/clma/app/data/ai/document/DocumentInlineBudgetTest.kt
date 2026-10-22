package com.clma.app.data.ai.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentInlineBudgetTest {
    @Test
    fun `unknown window falls back to the 128k default and the default output reserve`() {
        // (128_000 - 16_000) * 90% = 100_800
        assertEquals(100_800L, DocumentInlineBudget.availableTokens(null, null, 0))
        assertEquals(100_800L, DocumentInlineBudget.availableTokens(0, -1, 0))
    }

    @Test
    fun `available budget subtracts output reserve, margin and the rest of the request`() {
        // (1_000_000 - 8_000) * 90% - 50_000 = 842_800
        assertEquals(842_800L, DocumentInlineBudget.availableTokens(1_000_000, 8_000, 50_000))
    }

    @Test
    fun `output reserve never exceeds half the window and budget never goes negative`() {
        // reserve clamped to 5_000: (10_000 - 5_000) * 90% = 4_500
        assertEquals(4_500L, DocumentInlineBudget.availableTokens(10_000, 100_000, 0))
        assertEquals(0L, DocumentInlineBudget.availableTokens(10_000, 1_000, 1_000_000))
    }

    @Test
    fun `small document fits whole`() {
        val result = DocumentInlineBudget.allocate(listOf(500L), available = 10_000)
        assertEquals(listOf(DocumentInlineBudget.Allocation(fitsWhole = true, tokens = 500)), result)
    }

    @Test
    fun `large document is cut to the remaining budget minus the notice reserve`() {
        val result = DocumentInlineBudget.allocate(listOf(50_000L), available = 10_000)
        assertEquals(
            listOf(DocumentInlineBudget.Allocation(fitsWhole = false, tokens = 10_000L - DocumentInlineBudget.NOTICE_RESERVE_TOKENS)),
            result,
        )
    }

    @Test
    fun `newest document is served first and older ones get what is left`() {
        // Newest first: 6_000 fits, the older 6_000 only gets 4_000 - reserve.
        val result = DocumentInlineBudget.allocate(listOf(6_000L, 6_000L), available = 10_000)
        assertTrue(result[0].fitsWhole)
        assertEquals(6_000L, result[0].tokens)
        assertFalse(result[1].fitsWhole)
        assertEquals(4_000L - DocumentInlineBudget.NOTICE_RESERVE_TOKENS, result[1].tokens)
    }

    @Test
    fun `exhausted budget still leaves a minimum preview`() {
        val result = DocumentInlineBudget.allocate(listOf(5_000L, 5_000L), available = 5_000)
        assertTrue(result[0].fitsWhole)
        assertEquals(DocumentInlineBudget.Allocation(false, DocumentInlineBudget.MIN_PREVIEW_TOKENS.toLong()), result[1])
    }

    @Test
    fun `head of CJK text counts one token per character`() {
        val head = DocumentInlineBudget.headWithinTokens("文".repeat(100), 30)
        assertEquals(30, head.chars)
        assertEquals("文".repeat(30), head.text)
        assertEquals(30L, DocumentInlineBudget.estimateTokens(head.text))
    }

    @Test
    fun `head of ASCII text counts three characters per token`() {
        val head = DocumentInlineBudget.headWithinTokens("a".repeat(100), 10)
        assertEquals(30, head.chars)
    }

    @Test
    fun `head cut mid-line continues on that same line`() {
        val text = "a".repeat(10) + "\n" + "b".repeat(100)
        // 20 tokens = 60 ASCII chars: line 1 (11 chars) + 49 chars of line 2. The only newline is
        // far from the cut, so the head ends inside line 2 and reading resumes at line 2.
        val head = DocumentInlineBudget.headWithinTokens(text, 20)
        assertEquals(60, head.chars)
        assertEquals(text.substring(0, 60), head.text)
        assertEquals(2, head.lastLine)
        assertEquals(2, head.nextLine)
    }

    @Test
    fun `head snaps to a line break in the last fifth`() {
        val text = "a".repeat(29) + "\n" + "b".repeat(100)
        // 11 tokens = 33 ASCII chars; the newline at index 29 is within the last 20% of 33.
        val head = DocumentInlineBudget.headWithinTokens(text, 11)
        assertEquals(30, head.chars)
        assertEquals(1, head.lastLine)
        assertEquals(2, head.nextLine)
    }

    @Test
    fun `line count matches workspace_read_file`() {
        assertEquals(0, DocumentInlineBudget.lineCount(""))
        assertEquals(1, DocumentInlineBudget.lineCount("a"))
        assertEquals(2, DocumentInlineBudget.lineCount("a\nb\n"))
    }

    @Test
    fun `shown percent is clamped between 1 and 99`() {
        assertEquals(0, DocumentInlineBudget.shownPercent(0, 100))
        assertEquals(1, DocumentInlineBudget.shownPercent(1, 1_000))
        assertEquals(25, DocumentInlineBudget.shownPercent(250, 1_000))
        assertEquals(99, DocumentInlineBudget.shownPercent(999_999, 1_000_000))
    }

    @Test
    fun `truncation notice states size, percentage, path and how to continue`() {
        val head = DocumentInlineBudget.Head(text = "x", chars = 250, lastLine = 10, nextLine = 11)
        val notice = DocumentInlineBudget.truncationNotice(
            shownChars = 250,
            totalChars = 1_000,
            totalTokens = 400,
            totalLines = 40,
            head = head,
            fullTextPath = "/upload/.extracted/a.docx.1-2.txt",
        )
        assertTrue(notice.contains("250 of 1000 characters (about 25%)"))
        assertTrue(notice.contains("~400 tokens"))
        assertTrue(notice.contains("/upload/.extracted/a.docx.1-2.txt (40 lines"))
        assertTrue(notice.contains("offset=11"))
        assertTrue(notice.contains("workspace_read_file"))
    }

    @Test
    fun `truncation notice without a file tells the model to inform the user`() {
        val head = DocumentInlineBudget.Head(text = "x", chars = 10, lastLine = 1, nextLine = 1)
        val notice = DocumentInlineBudget.truncationNotice(10, 100, 50, 3, head, fullTextPath = null)
        assertTrue(notice.contains("not available"))
        assertTrue(notice.contains("first 10%"))
        assertFalse(notice.contains("offset="))
    }
}
