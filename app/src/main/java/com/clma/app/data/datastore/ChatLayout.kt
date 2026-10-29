package com.clma.app.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Chat page layout. [GEMINI] is the default: a flat header, no avatars on either side of the
 * conversation, the user's turn as a right-aligned capsule and the assistant's as a plain
 * centred reading column - the layout Gemini uses. [ORIGINAL] is the long-standing look that
 * [GEMINI] replaced as the default; [CLEAN] is the flat header + two-row input box (the old
 * `cleanLayout` switch); [CLAUDE] follows Claude.ai: an even quieter header, no assistant bubble,
 * a narrow centred reading column, a 24dp composer card and a coral send button.
 */
@Serializable
enum class ChatLayout {
    @SerialName("original")
    ORIGINAL,

    @SerialName("gemini")
    GEMINI,

    @SerialName("clean")
    CLEAN,

    @SerialName("claude")
    CLAUDE,
}

/**
 * Effective layout. [chatLayout] is null until the user picks one in the selector. Installs that
 * only knew the old [DisplaySetting.cleanLayout] switch keep whatever it said; an install that
 * never touched either lands on [ChatLayout.GEMINI], which is the default look from here on.
 */
fun resolveChatLayout(chatLayout: ChatLayout?, cleanLayout: Boolean): ChatLayout =
    chatLayout ?: if (cleanLayout) ChatLayout.CLEAN else ChatLayout.GEMINI

/**
 * Applies a selector pick. The legacy switch is kept in step for the two layouts it can express,
 * so an older build reading the same settings still shows the closest thing; GEMINI and CLAUDE
 * also map to the clean switch on, since the flat header/two-row box is the closer fallback than
 * the original chrome.
 */
fun DisplaySetting.withChatLayout(layout: ChatLayout): DisplaySetting = copy(
    chatLayout = layout,
    cleanLayout = layout != ChatLayout.ORIGINAL,
)

val DisplaySetting.effectiveChatLayout: ChatLayout
    get() = resolveChatLayout(chatLayout, cleanLayout)

/** True when the Gemini-style chat chrome (no avatars, capsule user turn, flat header) applies. */
val DisplaySetting.usesGeminiChatUi: Boolean
    get() = effectiveChatLayout == ChatLayout.GEMINI
