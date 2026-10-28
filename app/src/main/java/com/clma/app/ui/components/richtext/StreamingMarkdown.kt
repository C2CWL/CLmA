package com.clma.app.ui.components.richtext

internal data class StreamingMarkdownSplit(val prefix: String, val tail: String)

/** 只提交已由空行封闭的顶层块；容器、跨块引用及公式保守留在活动尾部。 */
internal fun streamingMarkdownSplit(content: String): StreamingMarkdownSplit {
    // HTML 和引用定义可能改变前文的解析，不能分别建立 AST。
    if ('<' in content || '[' in content || '$' in content || "\\(" in content) {
        return StreamingMarkdownSplit("", content)
    }
    var offset = 0
    var boundary = 0
    var fence: Char? = null
    var fenceLength = 0
    var container = false
    while (offset < content.length) {
        val end = content.indexOf('\n', offset)
        if (end < 0) break // 尚未完成的行不能成为稳定边界。
        val line = content.substring(offset, end).trimEnd('\r')
        val text = line.trimStart(' ')
        val indent = line.length - text.length
        val marker = text.firstOrNull()
        val run = if (marker == '`' || marker == '~') text.takeWhile { it == marker }.length else 0
        if (fence != null) {
            if (indent <= 3 && marker == fence && run >= fenceLength && text.drop(run).isBlank()) {
                fence = null
            }
        } else if (indent <= 3 && run >= 3) {
            fence = marker
            fenceLength = run
        } else if (text.isBlank()) {
            if (!container) boundary = end + 1
        } else {
            val digits = text.takeWhile { it.isDigit() }.length
            val ordered = digits in 1..9 && text.getOrNull(digits) in listOf('.', ')') &&
                (text.getOrNull(digits + 1)?.isWhitespace() == true || text.length == digits + 1)
            val bullet = marker in listOf('-', '+', '*') &&
                (text.getOrNull(1)?.isWhitespace() == true || text.length == 1)
            if ('`' in text || '*' in text || '_' in text || indent >= 4 || line.startsWith('\t') || marker == '>' || bullet || ordered) {
                // 列表可在空行后继续（甚至懒惰续行）；不猜测其闭合位置。
                container = true
            }
        }
        offset = end + 1
    }
    return StreamingMarkdownSplit(content.substring(0, boundary), content.substring(boundary))
}
