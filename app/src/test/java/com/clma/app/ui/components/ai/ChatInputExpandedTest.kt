package com.clma.app.ui.components.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The input bar rests as a one-line pill in every chat layout (原版 / 简洁 / 暖阳) and opens only
 * while focused, carrying attachments, or holding a multi-line draft.
 */
class ChatInputExpandedTest {

    @Test
    fun `empty unfocused bar stays collapsed`() {
        assertFalse(chatInputExpanded(focused = false, hasAttachments = false, multiLineDraft = false))
    }

    @Test
    fun `focus opens the bar`() {
        assertTrue(chatInputExpanded(focused = true, hasAttachments = false, multiLineDraft = false))
    }

    @Test
    fun `attachments open the bar`() {
        assertTrue(chatInputExpanded(focused = false, hasAttachments = true, multiLineDraft = false))
    }

    @Test
    fun `a multi-line draft keeps the bar open`() {
        assertTrue(chatInputExpanded(focused = false, hasAttachments = false, multiLineDraft = true))
    }
}
