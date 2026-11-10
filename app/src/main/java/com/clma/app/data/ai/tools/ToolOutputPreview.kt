package com.clma.app.data.ai.tools

/** Keeps both ends of a log; the character budget excludes the omission marker. */
internal fun toolOutputPreview(
    text: String,
    maxChars: Int,
): String {
    require(maxChars > 0)
    if (text.length <= maxChars) return text
    var headEnd = maxChars / 2
    var tailStart = text.length - (maxChars - headEnd)
    if (headEnd > 0 && text[headEnd - 1].isHighSurrogate() && text[headEnd].isLowSurrogate()) headEnd--
    if (tailStart > 0 && text[tailStart - 1].isHighSurrogate() && text[tailStart].isLowSurrogate()) tailStart++
    val omitted = tailStart - headEnd
    return text.substring(0, headEnd) + "\n…[$omitted characters omitted; head and tail shown]…\n" + text.substring(tailStart)
}
