package com.clma.app.service.chatbox

/**
 * Colours of the edge-bar chat box. Light = milky white glass with dark text, dark = charcoal
 * glass with light text; [glass] picks the translucent tint used over a cross-window blur,
 * otherwise the near-opaque fallback (no blur behind, so the box must stay readable by itself).
 */
data class ChatBoxPalette(
    val surface: Int,
    val rim: Int,
    val title: Int,
    val subtitle: Int,
    val aiBubble: Int,
    val aiText: Int,
    val toolChip: Int,
    val toolText: Int,
    val statusBg: Int,
    val statusText: Int,
    val accent: Int,
    val control: Int,
    val controlIcon: Int,
    val inputBg: Int,
    val inputBorder: Int,
    val inputText: Int,
    val hint: Int,
    val stopBg: Int,
    val stopText: Int,
    val doneText: Int,
) {
    companion object {
        const val USER_BUBBLE_START = 0xFF5E7BFF.toInt()
        const val USER_BUBBLE_END = 0xFF8A6DFF.toInt()
        const val SEND_START = 0xFF6F8CFF.toInt()
        const val SEND_END = 0xFF9B7BFF.toInt()

        /** Light glass: white ~66% with a slight cool tint. */
        const val LIGHT_GLASS = 0xA8FAFBFE.toInt()

        /** Light fallback: #F7F8FB at 96%. */
        const val LIGHT_OPAQUE = 0xF5F7F8FB.toInt()

        /** Dark glass: charcoal ~70%. */
        const val DARK_GLASS = 0xB318191E.toInt()

        /** Dark fallback: #18191E at 96%. */
        const val DARK_OPAQUE = 0xF518191E.toInt()

        fun of(dark: Boolean, glass: Boolean): ChatBoxPalette = if (dark) {
            ChatBoxPalette(
                surface = if (glass) DARK_GLASS else DARK_OPAQUE,
                rim = 0x1CFFFFFF,
                title = 0xFFFFFFFF.toInt(),
                subtitle = 0xFF9AA0AD.toInt(),
                aiBubble = 0x14FFFFFF,
                aiText = 0xFFE6E8EE.toInt(),
                toolChip = 0x0DFFFFFF,
                toolText = 0xFF9298A6.toInt(),
                statusBg = 0x178FB4FF,
                statusText = 0xFFCFD6FF.toInt(),
                accent = 0xFF8FB4FF.toInt(),
                control = 0x1AFFFFFF,
                controlIcon = 0xFFE8EAF0.toInt(),
                inputBg = 0x14FFFFFF,
                inputBorder = 0x1CFFFFFF,
                inputText = 0xFFFFFFFF.toInt(),
                hint = 0xFF80869A.toInt(),
                stopBg = 0x2EFF3B30,
                stopText = 0xFFFF6B61.toInt(),
                doneText = 0xFF7FE3A0.toInt(),
            )
        } else {
            ChatBoxPalette(
                surface = if (glass) LIGHT_GLASS else LIGHT_OPAQUE,
                rim = 0xEBFFFFFF.toInt(),
                title = 0xFF1C1C1E.toInt(),
                subtitle = 0xFF6E727C.toInt(),
                aiBubble = 0x1C787D8C,
                aiText = 0xFF1C1C1E.toInt(),
                toolChip = 0x17787D8C,
                toolText = 0xFF6B6F7A.toInt(),
                statusBg = 0x145A6EFF,
                statusText = 0xFF3446C9.toInt(),
                accent = 0xFF4A5EE8.toInt(),
                control = 0xBFFFFFFF.toInt(),
                controlIcon = 0xFF3A3C44.toInt(),
                inputBg = 0xBFFFFFFF.toInt(),
                inputBorder = 0x12000000,
                inputText = 0xFF1C1C1E.toInt(),
                hint = 0xFF8A8E99.toInt(),
                stopBg = 0xFFFF3B30.toInt(),
                stopText = 0xFFFFFFFF.toInt(),
                doneText = 0xFF2E9E5B.toInt(),
            )
        }
    }
}
