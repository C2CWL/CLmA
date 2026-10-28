package com.clma.app.ui.components.richtext

import org.junit.Assert.*
import org.junit.Test

class StreamingMarkdownTest {
    private fun check(content: String, prefix: String) {
        val split = streamingMarkdownSplit(content)
        assertEquals(prefix, split.prefix)
        assertEquals(content, split.prefix + split.tail)
    }

    @Test fun `completed paragraph leaves active tail`() = check("first\n\nsecond", "first\n\n")
    @Test fun `unfinished blank line stays active`() = check("first\n ", "")
    @Test fun `empty content`() = check("", "")
    @Test fun `crlf is preserved`() = check("first\r\n\r\nsecond", "first\r\n\r\n")
    @Test fun `unclosed fence keeps internal empty lines`() = check("intro\n\n```kt\nx\n\ny", "intro\n\n")
    @Test fun `closed fence can become stable`() = check("```kt\nx\n\n```\n\ntail", "```kt\nx\n\n```\n\n")
    @Test fun `short fence does not close long fence`() = check("````\nx\n```\n\ntail", "")
    @Test fun `tilde fence works`() = check("~~~\nx\n~~~\n\ntail", "~~~\nx\n~~~\n\n")
    @Test fun `list continuations stay together`() = check("intro\n\n- one\n\n  two\n\ntail", "intro\n\n")
    @Test fun `ordered list stays together`() = check("1. one\n\n2. two\n\ntail", "")
    @Test fun `quote lazy continuation stays together`() = check("> one\nlazy\n\n> two\n\ntail", "")
    @Test fun `indented code stays together`() = check("    one\n\n    two\n\ntail", "")
    @Test fun `table stays intact`() = check("a | b\n--- | ---\nx | y\n\ntail", "a | b\n--- | ---\nx | y\n\n")
    @Test fun `setext heading stays intact`() = check("heading\n===\n\ntail", "heading\n===\n\n")
    @Test fun `html falls back`() = check("intro\n\n<div>tail", "")
    @Test fun `reference definition falls back`() = check("[link][id]\n\n[id]: /url", "")
    @Test fun `math falls back`() = check("intro\n\n\$\$x\n\ny\$\$", "")
    @Test fun `multiline inline code stays together`() = check("`one\n\ntwo`\n\ntail", "")
}
