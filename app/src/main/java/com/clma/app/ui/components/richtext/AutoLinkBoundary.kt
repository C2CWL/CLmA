package com.clma.app.ui.components.richtext

/**
 * GFM 自动链接的 lexer 用 `\S` 匹配 URL 路径，会把紧跟其后的中文、中文标点乃至整句中文
 * 一起吞进同一个 GFM_AUTOLINK token；而 lexer 的 `pushbackAutolink` 只裁剪 ASCII 结尾
 * 字符（.,:;!?"'*_~]`），中文标点不在其中。结果就是链接样式蔓延到正文。
 *
 * GFM 规范中裸链接只由 ASCII 字符组成，因此在渲染前按首个非 ASCII（或空白）字符切断，
 * 前半段作为链接 URL，后半段作为普通文本渲染。
 */
internal data class AutoLinkSplit(val url: String, val trailingText: String)

private const val AUTOLINK_BAD_ENDING = ".,:;!?\"'*_~]`"

internal fun splitGfmAutoLink(token: String): AutoLinkSplit {
    val boundary = token.indexOfFirst { it.code > 0x7F || it.isWhitespace() }
    val (urlPart, trailing) = if (boundary < 0) {
        token to ""
    } else {
        token.substring(0, boundary) to token.substring(boundary)
    }

    // 与 lexer 的 pushbackAutolink 保持一致：结尾的 ASCII 标点不属于链接。
    var end = urlPart.length
    while (end > 0 && urlPart[end - 1] in AUTOLINK_BAD_ENDING) {
        end--
    }
    if (end == urlPart.length) {
        return AutoLinkSplit(urlPart, trailing)
    }
    if (end == 0) {
        return AutoLinkSplit("", urlPart + trailing)
    }
    return AutoLinkSplit(urlPart.substring(0, end), urlPart.substring(end) + trailing)
}
