package com.clma.app.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Chat page layout. [ORIGINAL] is the long-standing look; [CLEAN] is the flat header + two-row
 * input box (the old `cleanLayout` switch); [CLAUDE] follows Claude.ai: an even quieter header,
 * no assistant bubble, a narrow centred reading column, a 24dp composer card and a coral send
 * button.
 */
@Serializable
enum class ChatLayout {
    @SerialName("original")
    ORIGINAL,

    @SerialName("clean")
    CLEAN,

    @SerialName("claude")
    CLAUDE,
}

/**
 * Effective layout. [chatLayout] is null until the user picks one in the selector, so installs
 * that only knew the old [DisplaySetting.cleanLayout] switch keep whatever it said.
 */
fun resolveChatLayout(chatLayout: ChatLayout?, cleanLayout: Boolean): ChatLayout =
    chatLayout ?: if (cleanLayout) ChatLayout.CLEAN else ChatLayout.ORIGINAL

/**
 * Applies a selector pick. The legacy switch is kept in step for the two layouts it can express,
 * so an older build reading the same settings still shows the closest thing; CLAUDE also maps to
 * the clean switch on, since the flat header/two-row box is the closer fallback than the
 * original chrome.
 */
fun DisplaySetting.withChatLayout(layout: ChatLayout): DisplaySetting = copy(
    chatLayout = layout,
    cleanLayout = layout != ChatLayout.ORIGINAL,
)

val DisplaySetting.effectiveChatLayout: ChatLayout
    get() = resolveChatLayout(chatLayout, cleanLayout)
