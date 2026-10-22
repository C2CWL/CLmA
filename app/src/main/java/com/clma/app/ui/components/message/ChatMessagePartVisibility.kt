package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart

/**
 * 这个 Text part 在界面上是不是什么都显示不出来。
 *
 * 流式时 TextStart 会先插入一个空 Text，网络抖动重试、换模型续写、或者模型在工具调用前
 * 吐一个 "\n"，都会让这种空白 Text 夹在工具步骤中间。它渲染出来只是一个空气泡，还会把
 * 连续的工具步骤切成好几组，每组只剩两三步，折叠头也就没了。
 *
 * 只在界面上把它当透明，数据里保留：Gemini 的 thought signature 之类的 metadata 可能挂在
 * 空 Text 上，后续请求还要带回去。带 `rikkahub.webview` 的 Text 渲染成卡片，不算空白。
 */
internal fun UIMessagePart.Text.isVisuallyBlank(): Boolean =
    text.isBlank() && metadata?.containsKey("rikkahub.webview") != true
