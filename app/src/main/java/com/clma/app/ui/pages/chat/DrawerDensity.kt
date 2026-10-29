package com.clma.app.ui.pages.chat

import androidx.compose.ui.unit.Density

/**
 * Density handed to [androidx.compose.material3.ModalNavigationDrawer]: the same pixel density
 * as [density], with the font scale fixed at 1.
 *
 * Material3's ModalNavigationDrawer keeps its closed-anchor `minValue` in `remember(density)` but
 * its `anchorsInitialized` flag in a plain `remember`. When only the font scale changes (e.g. the
 * stored UI font size loads after the first cold-start layout and the theme provides a new
 * LocalDensity), `minValue` resets to 0 while the anchors stay the same, so it is never restored.
 * The drawer then places neither the sheet nor the scrim until the offset reaches 0, which shows
 * up as a flicker at the end of every open. The drawer's anchors depend only on pixel density, so
 * giving it a font-scale-independent density keeps that state stable. The app's real density is
 * provided again to the drawer's content so the user's font size still applies inside.
 */
internal fun drawerAnchorDensity(density: Density): Density = Density(density.density, 1f)
