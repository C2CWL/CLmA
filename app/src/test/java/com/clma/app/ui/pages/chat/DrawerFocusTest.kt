package com.clma.app.ui.pages.chat

import androidx.compose.material3.DrawerValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerFocusTest {

    @Test
    fun `opening with an unfocused input leaves focus alone`() {
        assertFalse(shouldClearFocusOnDrawerOpen(DrawerValue.Open, inputFocused = false))
    }

    @Test
    fun `opening with a focused input clears focus`() {
        assertTrue(shouldClearFocusOnDrawerOpen(DrawerValue.Open, inputFocused = true))
    }

    @Test
    fun `closing never clears focus`() {
        assertFalse(shouldClearFocusOnDrawerOpen(DrawerValue.Closed, inputFocused = false))
        assertFalse(shouldClearFocusOnDrawerOpen(DrawerValue.Closed, inputFocused = true))
    }
}
