package com.clma.app.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Chat page layout. [GEMINI] is the default: a flat header, no avatars on either side of the
 * conversation, the user's turn as a right-aligned capsule and the assistant's as a plain
 * centred reading column - the layout Gemini uses. [ORIGINAL] is retained only to decode old
 * settings and resolves to [GEMINI]; [CLEAN] is the 「现代」 layout: floating rounded top bar and input card, no assistant bubble (the old
 * `cleanLayout` switch); [CLAUDE] follows Claude.ai: an even quieter header, no assistant bubble,
 * a narrow centred reading column, a 24dp composer card and a coral send button.
 */
@Serializable
enum class ChatLayout {
    /** Legacy serialized value; not a selectable layout. */
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
    when (chatLayout) {
        ChatLayout.ORIGINAL -> ChatLayout.GEMINI
        null -> if (cleanLayout) ChatLayout.CLEAN else ChatLayout.GEMINI
        else -> chatLayout
    }

/**
 * Applies a selector pick, normalizing the retired original value to GEMINI. All supported
 * layouts keep the legacy clean switch on for the closest fallback in older builds.
 */
fun DisplaySetting.withChatLayout(layout: ChatLayout): DisplaySetting = copy(
    chatLayout = resolveChatLayout(layout, cleanLayout),
    cleanLayout = true,
)

val DisplaySetting.effectiveChatLayout: ChatLayout
    get() = resolveChatLayout(chatLayout, cleanLayout)

/** True when the Gemini-style chat chrome (no avatars, capsule user turn, flat header) applies. */
val DisplaySetting.usesGeminiChatUi: Boolean
    get() = effectiveChatLayout == ChatLayout.GEMINI

/**
 * True when the 「现代」 chat chrome applies. It reuses the [ChatLayout.CLEAN] value (serialized
 * as "clean") so installs that picked the old 简洁 layout land on the new look with no migration:
 * floating rounded header, tonal surfaces instead of hairlines, a floating 20dp composer card.
 */
val DisplaySetting.usesModernChatUi: Boolean
    get() = effectiveChatLayout == ChatLayout.CLEAN
