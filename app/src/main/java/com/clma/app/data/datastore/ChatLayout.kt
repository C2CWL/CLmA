package com.clma.app.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Chat page layout. [GEMINI] is the default: a flat header, no avatars on either side of the
 * conversation, the user's turn as a right-aligned capsule and the assistant's as a plain
 * centred reading column - the layout Gemini uses. [ORIGINAL] is retained only to decode old
 * settings and resolves to [GEMINI]; [CLEAN] is the flat header + two-row input box (the old
 * `cleanLayout` switch); [MODERN] (「现代」) has a floating rounded top bar and input card, no
 * assistant bubble and soft tinted user bubbles.
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

    /**
     * 「现代」. Serialized as "claude": it took the slot of the retired 暖阳 (Claude-style) layout,
     * so an install that had 暖阳 picked lands on 现代 with no migration.
     */
    @SerialName("claude")
    MODERN,
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
 * True when the 「现代」 chat chrome applies ([ChatLayout.MODERN], stored as "claude"): floating
 * rounded header, tonal surfaces instead of hairlines, a floating 20dp composer card.
 */
val DisplaySetting.usesModernChatUi: Boolean
    get() = effectiveChatLayout == ChatLayout.MODERN
