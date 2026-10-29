package com.clma.app.ui.pages.chat

import androidx.compose.material3.DrawerValue

/**
 * Whether opening the chat drawer should clear input focus.
 *
 * Only when the drawer is heading to [DrawerValue.Open] and the chat text field really holds
 * focus. Clearing unconditionally (and with force) also drops the window's view focus on every
 * open, which the drawer's own requestFocus() then takes back as it settles; on a fresh window
 * that round trip showed up as a one-frame flash of the fully opened drawer.
 */
internal fun shouldClearFocusOnDrawerOpen(target: DrawerValue, inputFocused: Boolean): Boolean =
    target == DrawerValue.Open && inputFocused
