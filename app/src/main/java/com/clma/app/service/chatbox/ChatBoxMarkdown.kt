package com.clma.app.service.chatbox

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.StrikethroughSpan

/**
 * Lightweight, allocation-friendly Markdown formatter for the edge-bar chat box.
 *
 * Designed to parse recent messages in <0.2ms without dragging in heavy AST engines:
 * - Strips syntax wrappers (`**`, `` ` ``, ```` ``` ````, `# `).
 * - Converts bold, inline code, fenced code blocks, headers, and strikethroughs to Spans.
 * - Leaves unclosed delimiters literal so streaming output does not flicker or truncate prematurely.
 *
 * Core parsing is pure Kotlin so it runs in JVM unit tests without Robolectric.
 */
object ChatBoxMarkdown {

    enum class SpanType {
        BOLD,
        ITALIC,
        BOLD_ITALIC,
        CODE,
        CODE_BLOCK,
        STRIKE,
    }

    data class Span(
        val type: SpanType,
        val start: Int,
        val end: Int,
    )

    data class ParseResult(
        val text: String,
        val spans: List<Span>,
    )

    /**
     * Parses Markdown syntax into clean text and a list of character spans.
     * Pure Kotlin function safe for standard JVM tests.
     */
    fun parse(raw: String): ParseResult {
        if (raw.isEmpty()) return ParseResult("", emptyList())

        val lines = raw.split('\n')
        val out = StringBuilder(raw.length)
        val spans = mutableListOf<Span>()

        var inCodeBlock = false
        var codeBlockStart = -1

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (!inCodeBlock) {
                    inCodeBlock = true
                    codeBlockStart = -1
                    continue
                } else {
                    inCodeBlock = false
                    val codeBlockEnd = out.length
                    if (codeBlockStart != -1 && codeBlockEnd > codeBlockStart) {
                        spans.add(Span(SpanType.CODE_BLOCK, codeBlockStart, codeBlockEnd))
                    }
                    codeBlockStart = -1
                    continue
                }
            }

            if (inCodeBlock) {
                if (out.isNotEmpty()) out.append('\n')
                val lineStart = out.length
                if (codeBlockStart == -1) codeBlockStart = lineStart
                out.append(line)
                continue
            }

            // Header check: 1 to 6 '#' followed by space
            var hIdx = 0
            while (hIdx < line.length && hIdx < 3 && line[hIdx] == ' ') hIdx++
            val hStart = hIdx
            while (hIdx < line.length && line[hIdx] == '#') hIdx++
            val hCount = hIdx - hStart

            var isHeader = false
            var content = line
            if (hCount in 1..6 && hIdx < line.length && line[hIdx] == ' ') {
                isHeader = true
                while (hIdx < line.length && line[hIdx] == ' ') hIdx++
                content = line.substring(hIdx)
            }

            if (out.isNotEmpty()) out.append('\n')

            val lineStart = out.length
            parseInline(content, out, spans)
            val lineEnd = out.length

            if (isHeader && lineEnd > lineStart) {
                spans.add(Span(SpanType.BOLD, lineStart, lineEnd))
            }
        }

        if (inCodeBlock && codeBlockStart != -1) {
            val codeBlockEnd = out.length
            if (codeBlockEnd > codeBlockStart) {
                spans.add(Span(SpanType.CODE_BLOCK, codeBlockStart, codeBlockEnd))
            }
        }

        return ParseResult(out.toString(), spans)
    }

    private fun parseInline(text: String, out: StringBuilder, spans: MutableList<Span>) {
        var i = 0
        val n = text.length
        while (i < n) {
            // Inline code `...`
            if (text[i] == '`') {
                val endTick = text.indexOf('`', i + 1)
                if (endTick != -1) {
                    val codeContent = text.substring(i + 1, endTick)
                    val startPos = out.length
                    out.append(codeContent)
                    val endPos = out.length
                    if (endPos > startPos) {
                        spans.add(Span(SpanType.CODE, startPos, endPos))
                    }
                    i = endTick + 1
                    continue
                } else {
                    out.append('`')
                    i++
                    continue
                }
            }

            // Bold & Italic ***...***
            if (text.startsWith("***", i)) {
                val endDelim = text.indexOf("***", i + 3)
                if (endDelim != -1) {
                    val inner = text.substring(i + 3, endDelim)
                    val startPos = out.length
                    parseInline(inner, out, spans)
                    val endPos = out.length
                    if (endPos > startPos) {
                        spans.add(Span(SpanType.BOLD_ITALIC, startPos, endPos))
                    }
                    i = endDelim + 3
                    continue
                }
            }

            // Bold **...**
            if (text.startsWith("**", i)) {
                val endDelim = text.indexOf("**", i + 2)
                if (endDelim != -1) {
                    val inner = text.substring(i + 2, endDelim)
                    val startPos = out.length
                    parseInline(inner, out, spans)
                    val endPos = out.length
                    if (endPos > startPos) {
                        spans.add(Span(SpanType.BOLD, startPos, endPos))
                    }
                    i = endDelim + 2
                    continue
                }
            }

            // Bold __...__
            if (text.startsWith("__", i)) {
                val endDelim = text.indexOf("__", i + 2)
                if (endDelim != -1) {
                    val inner = text.substring(i + 2, endDelim)
                    val startPos = out.length
                    parseInline(inner, out, spans)
                    val endPos = out.length
                    if (endPos > startPos) {
                        spans.add(Span(SpanType.BOLD, startPos, endPos))
                    }
                    i = endDelim + 2
                    continue
                }
            }

            // Strikethrough ~~...~~
            if (text.startsWith("~~", i)) {
                val endDelim = text.indexOf("~~", i + 2)
                if (endDelim != -1) {
                    val inner = text.substring(i + 2, endDelim)
                    val startPos = out.length
                    parseInline(inner, out, spans)
                    val endPos = out.length
                    if (endPos > startPos) {
                        spans.add(Span(SpanType.STRIKE, startPos, endPos))
                    }
                    i = endDelim + 2
                    continue
                }
            }

            // Italic *...* (not adjacent to space or star)
            if (text[i] == '*') {
                if (i + 1 < n && text[i + 1] != '*' && text[i + 1] != ' ') {
                    val endDelim = text.indexOf('*', i + 1)
                    if (endDelim != -1 && text[endDelim - 1] != ' ' && (endDelim + 1 >= n || text[endDelim + 1] != '*')) {
                        val inner = text.substring(i + 1, endDelim)
                        val startPos = out.length
                        parseInline(inner, out, spans)
                        val endPos = out.length
                        if (endPos > startPos) {
                            spans.add(Span(SpanType.ITALIC, startPos, endPos))
                        }
                        i = endDelim + 1
                        continue
                    }
                }
            }

            out.append(text[i])
            i++
        }
    }

    /**
     * Converts a [ParseResult] into an Android [CharSequence] with style spans applied.
     */
    fun toSpannable(result: ParseResult, codeBgColor: Int = 0x1A808080): CharSequence {
        if (result.spans.isEmpty()) return result.text

        val ssb = SpannableStringBuilder(result.text)
        for (span in result.spans) {
            when (span.type) {
                SpanType.BOLD -> ssb.setSpan(
                    StyleSpan(Typeface.BOLD),
                    span.start,
                    span.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                SpanType.ITALIC -> ssb.setSpan(
                    StyleSpan(Typeface.ITALIC),
                    span.start,
                    span.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                SpanType.BOLD_ITALIC -> ssb.setSpan(
                    StyleSpan(Typeface.BOLD_ITALIC),
                    span.start,
                    span.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                SpanType.CODE -> {
                    ssb.setSpan(
                        TypefaceSpan("monospace"),
                        span.start,
                        span.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    ssb.setSpan(
                        BackgroundColorSpan(codeBgColor),
                        span.start,
                        span.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                SpanType.CODE_BLOCK -> {
                    ssb.setSpan(
                        TypefaceSpan("monospace"),
                        span.start,
                        span.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    ssb.setSpan(
                        BackgroundColorSpan(codeBgColor),
                        span.start,
                        span.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                SpanType.STRIKE -> ssb.setSpan(
                    StrikethroughSpan(),
                    span.start,
                    span.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return ssb
    }

    /**
     * High-level entry point: parses raw markdown and builds formatted spannable text.
     */
    fun format(raw: String, codeBgColor: Int = 0x1A808080): CharSequence {
        return toSpannable(parse(raw), codeBgColor)
    }
}
